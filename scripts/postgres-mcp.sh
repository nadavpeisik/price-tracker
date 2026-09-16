#!/usr/bin/env bash
#
# postgres-mcp.sh — launcher for the read-only Postgres MCP server (issue #261).
# Design notes, threat model and measurements live in the PR for #261.
#
set -euo pipefail

# mcp<2 is load-bearing: postgres-mcp does not constrain its own SDK dependency, and
# unpinned it resolves 2.x and dies on import. 0.3.0 carries CVE-2026-85620; no fixed
# release exists yet, so re-check the pin when one lands.
MCP_PKG="postgres-mcp==0.3.0"
MCP_SDK_PIN="mcp<2"

DB_HOST=127.0.0.1
DB_PORT=5432
DB_ROLE=grafana_reader

usage() {
  cat <<'EOF'
postgres-mcp.sh — launcher for the read-only Postgres MCP server (#261).

Usage:
  scripts/postgres-mcp.sh            Run the MCP server on stdio (how .mcp.json invokes it)
  scripts/postgres-mcp.sh --check    Verify config + connectivity, and that the role holds no
                                     table DML privileges. Exits nonzero if it does.
  scripts/postgres-mcp.sh -h|--help  Show this help

Reads POSTGRES_DB and GRAFANA_DB_PASSWORD from the repo-root .env and connects as
grafana_reader, the SELECT-only role from infra/postgres/init/create-grafana-role.sh.
On a Postgres volume created before #242 that role does not exist yet; create it with:

  docker compose exec postgres bash /docker-entrypoint-initdb.d/create-grafana-role.sh

Environment:
  PRICEHUNT_ENV_FILE  Path to the .env to read (default: repo-root .env, falling back to the
                      main checkout's when run from a worktree)
EOF
}

die() {
  echo "postgres-mcp.sh: $*" >&2
  exit 2
}

CHECK_ONLY=0
case "${1-}" in
  -h | --help)
    usage
    exit 0
    ;;
  --check) CHECK_ONLY=1 ;;
  "") ;;
  *) die "unknown argument '$1' (try --help)" ;;
esac
[[ $# -le 1 ]] || die "too many arguments (try --help)"

command -v uv >/dev/null 2>&1 || die "uv not found on PATH — see https://docs.astral.sh/uv/"
command -v python3 >/dev/null 2>&1 || die "python3 not found on PATH"

resolve_env_file() {
  if [[ -n "${PRICEHUNT_ENV_FILE-}" ]]; then
    echo "$PRICEHUNT_ENV_FILE"
    return
  fi
  local root common_dir
  root="$(git rev-parse --show-toplevel)"
  if [[ -f "$root/.env" ]]; then
    echo "$root/.env"
    return
  fi
  # A worktree has no .env of its own; the main checkout owns the common git dir.
  common_dir="$(git rev-parse --path-format=absolute --git-common-dir)"
  echo "${common_dir%/.git}/.env"
}

ENV_FILE="$(resolve_env_file)"
[[ -f "$ENV_FILE" ]] || die "no .env at $ENV_FILE — copy .env.example and fill it in"

# .env is parsed, never sourced: `. "$ENV_FILE"` executes it, so a password containing
# `;` or a backtick runs as shell. A function rather than an inline "$(python3 - <<PY)",
# because bash parses a command substitution's body and an apostrophe inside the heredoc
# reads there as an unterminated quote.
build_database_uri() {
  ENV_FILE="$ENV_FILE" ROLE="$DB_ROLE" HOST="$DB_HOST" PORT="$DB_PORT" python3 - <<'PY'
import os
import re
import sys
from urllib.parse import quote, urlencode

QUOTE_CHARS = '"' + "'"
WANTED = ("POSTGRES_DB", "GRAFANA_DB_PASSWORD")

values = {}
with open(os.environ["ENV_FILE"], encoding="utf-8-sig") as handle:
    for line in handle:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if key not in WANTED:
            continue
        value = value.strip()
        if value[:1] in QUOTE_CHARS:
            quote_char = value[0]
            closing = value.find(quote_char, 1)
            if closing == -1:
                sys.exit(f"postgres-mcp.sh: unterminated {quote_char} in {key}")
            remainder = value[closing + 1:].strip()
            if remainder and not remainder.startswith("#"):
                sys.exit(f"postgres-mcp.sh: unexpected text after the closing {quote_char} "
                         f"in {key}")
            value, quoted = value[1:closing], quote_char
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
            quoted = ""

        # Refused rather than guessed at: these are the only two forms where this parser
        # and `docker compose config` disagree, and a silent disagreement reads later as
        # "password authentication failed".
        if "\\" in value:
            sys.exit(f"postgres-mcp.sh: {key} contains a backslash, which Compose and this "
                     "parser read differently -- use a value without one")
        if "$" in value and quoted != "'":
            sys.exit(f"postgres-mcp.sh: {key} contains '$' outside single quotes, which "
                     f"Compose interpolates -- single-quote it: {key}='...'")
        values[key] = value

missing = [k for k in WANTED if not values.get(k)]
if missing:
    sys.exit(f"postgres-mcp.sh: {', '.join(missing)} not set in {os.environ['ENV_FILE']}")


def q(value):
    return quote(value, safe="")


host = os.environ["HOST"]
if ":" in host and not host.startswith("["):
    host = f"[{host}]"

# Neither the role nor restricted mode bounds query runtime on its own.
options = "-c statement_timeout=30s -c lock_timeout=5s -c idle_in_transaction_session_timeout=60s"
query = urlencode({"options": options}, quote_via=quote)

print(f"postgresql://{q(os.environ['ROLE'])}:{q(values['GRAFANA_DB_PASSWORD'])}"
      f"@{host}:{os.environ['PORT']}/{q(values['POSTGRES_DB'])}?{query}")
PY
}

DATABASE_URI="$(build_database_uri)"

# Built from nothing so the third-party server inherits no secret the caller exported:
# .env is commonly sourced into a dev shell, and GROQ_API_KEY is exported for the backend.
MINIMAL_ENV=(env -i PATH="$PATH" HOME="$HOME" DATABASE_URI="$DATABASE_URI")

if [[ "$CHECK_ONLY" == 1 ]]; then
  echo "env file:  $ENV_FILE" >&2
  echo "target:    ${DB_ROLE}@${DB_HOST}:${DB_PORT}" >&2
  echo "package:   $MCP_PKG (with '$MCP_SDK_PIN')" >&2
  "${MINIMAL_ENV[@]}" uv run --quiet --no-project \
    --with 'psycopg[binary]' python - >&2 <<'PY'
import os
import sys

import psycopg

# has_table_privilege, not information_schema: the latter misses privileges reaching the
# role through membership or PUBLIC.
WRITABLE = """
SELECT n.nspname || '.' || c.relname AS relation, p.privilege
FROM pg_class AS c
JOIN pg_namespace AS n ON n.oid = c.relnamespace
CROSS JOIN unnest(ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) AS p (privilege)
WHERE c.relkind IN ('r', 'p', 'v', 'f')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND has_table_privilege(current_user, c.oid, p.privilege)
ORDER BY relation, p.privilege
"""

try:
    connection = psycopg.connect(os.environ["DATABASE_URI"], connect_timeout=5)
except psycopg.OperationalError as exc:
    print(f"FAIL: cannot connect -- {str(exc).strip().splitlines()[0]}")
    sys.exit(1)

with connection as conn:
    with conn.cursor() as cur:
        cur.execute("SELECT current_user, current_database(), "
                    "to_regclass('public.flyway_schema_history')")
        user, database, history = cur.fetchone()
        if history is None:
            print(f"connectivity: OK as {user} on {database}; no Flyway history yet "
                  "(boot the backend once to create the schema)")
        else:
            cur.execute("SELECT count(*) FROM public.flyway_schema_history WHERE success")
            (applied,) = cur.fetchone()
            print(f"connectivity: OK as {user} on {database}, "
                  f"{applied} Flyway migrations applied")

        cur.execute(WRITABLE)
        writable = cur.fetchall()
        if writable:
            print(f"FAIL: {user} holds {len(writable)} table write privileges, expected none:")
            for relation, privilege in writable[:20]:
                print(f"  {privilege} on {relation}")
            sys.exit(1)
        print(f"table write privileges held by {user} (INSERT/UPDATE/DELETE/TRUNCATE): none")
PY
  exit 0
fi

# In the environment, not argv: `ps` is world-readable and the URI carries the password.
exec "${MINIMAL_ENV[@]}" uv tool run --quiet --from "$MCP_PKG" --with "$MCP_SDK_PIN" \
  postgres-mcp --access-mode=restricted
