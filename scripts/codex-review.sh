#!/usr/bin/env bash
#
# codex-review.sh — Local Codex code review before pushing.
#
# Sibling of agy-review.sh: a second, independent reviewer per issue #81. Both
# scripts get their diff from get-review-diff.sh, so Gemini and Codex review the
# exact same diff text — a disagreement between them then reflects different
# judgment, not different scope. Runs a READ-ONLY Codex review and prints the
# findings to stdout. It NEVER edits, stages, commits, or pushes — it only reviews.
#
set -euo pipefail

MODEL="${CODEX_REVIEW_MODEL:-}"                    # empty = use Codex CLI's configured default
REASONING="${CODEX_REVIEW_REASONING_EFFORT:-high}" # passed as -c model_reasoning_effort="<value>"
TIMEOUT="${CODEX_REVIEW_TIMEOUT:-240s}"
BASE_REF="${CODEX_REVIEW_BASE:-origin/main}"
FETCH="${CODEX_REVIEW_FETCH:-1}"
MAX_UNTRACKED="${CODEX_REVIEW_MAX_FILE_BYTES:-102400}"

usage() {
  cat <<'EOF'
codex-review.sh — Local Codex code review before pushing.

Runs a READ-ONLY Codex review of the changes you're about to push and prints the
findings to stdout. Never edits, stages, commits, or pushes.

Usage:
  scripts/codex-review.sh                 Review everything not yet on origin/main
                                          (committed + uncommitted + new untracked files)
  scripts/codex-review.sh --staged        Review only staged changes
  scripts/codex-review.sh HEAD~3..HEAD    Review an explicit git range
  scripts/codex-review.sh -h | --help     Show this help

Note: the script runs from the repo root, so explicit range arguments are resolved
relative to the repo root (price-tracker/), not your current directory.

Gets its diff from scripts/get-review-diff.sh — the same helper agy-review.sh uses —
so both reviewers see the exact same diff text. Findings are grouped HIGH/MEDIUM/LOW,
ending with a 'VERDICT:' line, matching agy-review.sh's format.

Environment:
  CODEX_REVIEW_MODEL  Model override passed as `-m <model>` (default: empty — use
                      Codex CLI's configured default).
  CODEX_REVIEW_REASONING_EFFORT
                      Passed as `-c model_reasoning_effort="<value>"` (default: high
                      — better critique quality for a second-opinion reviewer).
  CODEX_REVIEW_TIMEOUT
                      Best-effort wall-clock timeout for the codex call (default:
                      240s). Wraps the call with `timeout`/`gtimeout` if either is on
                      PATH (a `timeout` that isn't GNU coreutils, e.g. Windows'
                      timeout.exe, is ignored); otherwise no timeout is enforced.
  CODEX_REVIEW_BASE   Base ref for the default scope (default: origin/main).
  CODEX_REVIEW_FETCH  1 (default) refreshes the base ref with a best-effort
                      'git fetch'; set 0 to skip (offline / slow remote).
  CODEX_REVIEW_MAX_FILE_BYTES
                      Max size of an untracked file to include in full (default:
                      102400 = 100 KiB). Larger untracked files are listed but their
                      contents are skipped, to avoid bloating the diff.

Always runs `codex exec --sandbox read-only` — no sandbox toggle.

Exit codes:
  0    review produced (or nothing to review)
  1    usage / environment error
  2    review FAILED (codex error, timeout, or quota/rate-limit) — this is NOT a
       code review
  130  aborted by the user (Ctrl+C)
EOF
}

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
esac

# --- preconditions -----------------------------------------------------------
command -v codex >/dev/null 2>&1 || {
  echo "error: 'codex' (Codex CLI) not found on PATH" >&2
  exit 1
}
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "error: not inside a git repository" >&2
  exit 1
}

cd -- "$(git rev-parse --show-toplevel)"

# --- symlink-integrity check (warn-only) --------------------------------------
# Codex auto-loads AGENTS.md as project context. On a Windows checkout without
# core.symlinks, AGENTS.md (a symlink to CLAUDE.md) can check out as a plain text
# file containing only the literal string "CLAUDE.md" — Codex would then load that
# string as its entire project context. A real symlink is never a regular file, so
# -L distinguishes the two cases precisely (no size heuristics).
if [ -f AGENTS.md ] && [ ! -L AGENTS.md ] && [ "$(tr -d '\r' <AGENTS.md)" = "CLAUDE.md" ]; then
  echo "warning: AGENTS.md looks like a broken symlink (a text file containing only" >&2
  echo "         'CLAUDE.md'). Codex's project context may be just that string. Run:" >&2
  echo "           git config core.symlinks true && git checkout AGENTS.md" >&2
fi

# --- compute the diff (shared with agy-review.sh) -----------------------------
# The env assignments must live inside the command substitution — a bare prefix has
# no command name, so bash would treat them as ordinary shell variables and never
# export them to get-review-diff.sh's process.
RAW="$(REVIEW_BASE="$BASE_REF" \
  REVIEW_FETCH="$FETCH" \
  REVIEW_MAX_UNTRACKED_BYTES="$MAX_UNTRACKED" \
  scripts/get-review-diff.sh "$@")"

RANGE_DESC="$(head -n 1 <<<"$RAW" | sed 's/^# RANGE: //')"
DIFF="$(tail -n +3 <<<"$RAW")"

if ! grep -q '[^[:space:]]' <<<"$DIFF"; then
  echo "Nothing to review (empty diff for: $RANGE_DESC)."
  exit 0
fi

# Soft guard: very large diffs burn quota and may exceed context. Warn, then proceed.
diff_bytes=${#DIFF}
if [ "$diff_bytes" -gt 200000 ]; then
  echo "warning: large diff (~${diff_bytes} chars) — consider scoping with a range arg." >&2
fi

# --- build the prompt (mirrors agy-review.sh's, plus a Codex context note) ----
read -r -d '' INSTRUCTIONS <<'PROMPT_EOF' || true
You are a senior software engineer performing a focused pre-push code review of this
repository. Review the diff below as a competent reviewer would, judging it on its
own merits — correctness, edge cases, security, concurrency, resource handling, and
test coverage as appropriate. The diff includes surrounding context lines; base your
review on it.

This repository's AGENTS.md describes its conventions and architecture and has
already been loaded as project context. You are running with read-only filesystem
access — use it to read relevant source files and run git commands (log, grep, etc.)
for extra context if helpful, but the diff below defines the scope of this review.

Output only the review text. Do not propose running commands; just report findings.

Output format:
- Group findings by severity: HIGH first, then MEDIUM, then LOW.
- For each finding: location (path:line) — the issue in one or two sentences — a
  concrete suggested fix.
- If a severity level has no findings, write "none".
- Finish with a single line starting "VERDICT:" stating whether this is safe to push
  or has blockers.
PROMPT_EOF

PROMPT="$INSTRUCTIONS

=== DIFF (range: $RANGE_DESC) ===
$DIFF"

# --- run the review (read-only; prompt via stdin to avoid ARG_MAX limits) -----
errfile="$(mktemp)"
trap 'rm -f "$errfile"' EXIT

# Baseline of TRACKED files only (-uno): a read-only review must not mutate tracked files.
# Includes both unstaged (git diff) and staged (git diff --cached) changes, so a
# sandbox-escape that only touches the index (e.g. a stray `git add`) is still caught.
before="$(git status --porcelain -uno && git diff && git diff --cached)"

codex_args=(exec --sandbox read-only)
if [ -n "$MODEL" ]; then
  codex_args+=(-m "$MODEL")
fi
codex_args+=(-c "model_reasoning_effort=\"$REASONING\"")
codex_args+=(-)

# Best-effort timeout wrap. codex exec has no built-in timeout flag. timeout(1) isn't
# on macOS by default; gtimeout is (coreutils via brew). On Windows Git Bash/MSYS2,
# `timeout` resolves to the native C:\Windows\system32\timeout.exe (waits for a
# keypress, not a command wrapper) — only trust a `timeout` on PATH if it identifies
# as GNU coreutils; gtimeout is unambiguous. If neither is usable, skip wrapping —
# never hard-fail on a missing/wrong timeout binary.
TIMEOUT_BIN=""
if command -v timeout >/dev/null 2>&1 && timeout --version 2>/dev/null | grep -q 'GNU coreutils'; then
  TIMEOUT_BIN="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_BIN="gtimeout"
fi

set +e
if [ -n "$TIMEOUT_BIN" ]; then
  OUTPUT="$(printf '%s' "$PROMPT" | "$TIMEOUT_BIN" "$TIMEOUT" codex "${codex_args[@]}" 2>"$errfile")"
else
  OUTPUT="$(printf '%s' "$PROMPT" | codex "${codex_args[@]}" 2>"$errfile")"
fi
status=$?
set -e
ERR="$(cat "$errfile")"

after="$(git status --porcelain -uno && git diff && git diff --cached)"

# --- user abort (Ctrl+C) -------------------------------------------------------
if [ "$status" -eq 130 ]; then
  echo "Review aborted by user." >&2
  exit 130
fi

# --- timeout ---------------------------------------------------------------
# timeout/gtimeout exit 124 specifically on a timeout (vs. other nonzero exits).
if [ -n "$TIMEOUT_BIN" ] && [ "$status" -eq 124 ]; then
  {
    echo "================ REVIEW FAILED (timeout after $TIMEOUT) ================"
    echo "codex did not finish within $TIMEOUT. Increase CODEX_REVIEW_TIMEOUT, or"
    echo "scope the review with a smaller diff (e.g. --staged or an explicit range)."
    echo "========================================================================"
    echo "This is NOT a code review."
  } >&2
  exit 2
fi

# --- detect quota / auth / hard errors — never mistake these for a review ------
# codex exec echoes the ENTIRE session transcript to stderr, including the prompt we
# sent — the diff under review — between bare "user"/"codex" marker lines. A diff can
# legitimately contain phrases this regex matches (e.g. a diff touching this very
# script, whose source contains this regex literally, or application code that
# handles HTTP 429s) — scanning the full transcript would self-match on that echoed
# text. Restrict the scan to the session preamble (everything before the first bare
# "user" line), where genuine CLI-level errors (auth/rate-limit before or instead of
# a session) appear. If $ERR has no "user" line (e.g. codex failed before starting a
# session), the whole of $ERR is the preamble. Confirmed via a live false positive:
# reviewing agy-review.sh's diff matched this regex's own source text, echoed back.
ERR_PREAMBLE="$(awk '/^user$/{exit} {print}' <<<"$ERR")"
if [ "$status" -ne 0 ] || printf '%s' "$ERR_PREAMBLE" | grep -qiE \
  'rate[ _-]?limit(ed| exceeded| reached)?|usage limit|quota exceeded|exceeded your[^.]*quota|too many requests|(^|[^0-9])429([^0-9]|$)|not authenticated|authentication failed|invalid api key|please sign in|session expired'; then
  {
    echo "================ REVIEW FAILED (quota/error) ================"
    echo "codex exit status: $status   model: ${MODEL:-default}   reasoning: $REASONING"
    echo "--- stderr ---"
    printf '%s\n' "$ERR"
    if [ -n "$OUTPUT" ]; then
      echo "--- partial stdout ---"
      printf '%s\n' "$OUTPUT"
    fi
    echo "============================================================"
    echo "This is NOT a code review."
  } >&2
  exit 2
fi

# --- read-only safety net: fail if codex mutated the working tree --------------
# A read-only review must never change files. If the tree changed, --sandbox
# read-only failed to contain codex, or a concurrent process edited tracked files
# mid-review; treat it as a hard failure either way.
if [ "$before" != "$after" ]; then
  {
    echo "============== REVIEW FAILED (read-only violation) =============="
    echo "The working tree changed during the review — codex modified files despite"
    echo "--sandbox read-only, or a concurrent process edited tracked files mid-review."
    echo "Inspect with 'git status' / 'git diff' and do NOT commit until the cause is"
    echo "confirmed: this is NOT a valid review."
    echo "================================================================"
  } >&2
  exit 2
fi

# --- emit the review -----------------------------------------------------------
echo "# Codex review — model: ${MODEL:-default} — reasoning: $REASONING — range: $RANGE_DESC"
echo
printf '%s\n' "$OUTPUT"
