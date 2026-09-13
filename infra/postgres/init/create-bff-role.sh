#!/bin/bash
# Creates the role and schema the BFF's session store lives in (#247). Session rows carry
# Auth0 access and refresh tokens, so they get their own schema (bff_sessions) owned by
# their own login role (bff_session): the BFF cannot read the catalog in public, and
# grafana_reader's grants (create-grafana-role.sh: USAGE on public + default SELECT in
# public) never reach bff_sessions, so token material is invisible to Grafana.
#
# Known limit: the backend connects as POSTGRES_USER, the image's bootstrap superuser,
# which can read bff_sessions. The isolation here is BFF->catalog and Grafana->sessions,
# not backend->sessions; a least-privilege backend role is its own infra item.
#
# The postgres image runs this only when the data directory is initialized (first
# boot of a fresh volume). An EXISTING dev database never re-runs it — apply it
# once by hand instead (the container already carries the env vars):
#
#   docker compose exec postgres bash /docker-entrypoint-initdb.d/create-bff-role.sh
#
# Idempotent: safe to re-run; re-running also resets the password to the current
# BFF_DB_PASSWORD and re-asserts the schema owner.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
  -v bff_password="${BFF_DB_PASSWORD:?BFF_DB_PASSWORD not set}" \
  -v db="$POSTGRES_DB" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
SELECT format('CREATE ROLE bff_session LOGIN PASSWORD %L', :'bff_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'bff_session')
\gexec
ALTER ROLE bff_session WITH PASSWORD :'bff_password';
GRANT CONNECT ON DATABASE :"db" TO bff_session;
-- Two statements, not `CREATE SCHEMA IF NOT EXISTS ... AUTHORIZATION`: that form leaves a
-- pre-existing schema's owner alone, so a rerun would not be the repair it claims to be.
CREATE SCHEMA IF NOT EXISTS bff_sessions;
ALTER SCHEMA bff_sessions OWNER TO bff_session;
ALTER ROLE bff_session SET search_path = bff_sessions;
EOSQL
