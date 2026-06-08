#!/usr/bin/env bash
#
# agy-review.sh — Local Gemini code review (Google Antigravity CLI) before pushing.
#
# Runs a READ-ONLY Gemini review of the changes you are about to push and prints the
# findings to stdout. Meant to be run on-demand from the pre-push workflow (by Claude
# Code or by hand). It NEVER edits, stages, commits, or pushes — it only reviews.
#
# Why this exists: the Gemini code-assist GitHub bot sunsets 2026-07-17. Antigravity's
# `agy` keeps the same Gemini model family available locally, so we can get that review
# quality *before* the diff ever reaches GitHub.
#
set -euo pipefail

MODEL="${AGY_REVIEW_MODEL:-Gemini 3.5 Flash (High)}"
TIMEOUT="${AGY_REVIEW_TIMEOUT:-240s}"
SANDBOX="${AGY_REVIEW_SANDBOX:-1}" # 1 = run agy --sandbox (no command/file access); 0 = allow it
BASE_REF="${AGY_REVIEW_BASE:-origin/main}"

usage() {
  cat <<'EOF'
agy-review.sh — Local Gemini code review (Antigravity CLI) before pushing.

Runs a READ-ONLY Gemini review of the changes you're about to push and prints the
findings to stdout. Never edits, stages, commits, or pushes.

Usage:
  scripts/agy-review.sh                 Review everything not yet on origin/main
                                        (committed + uncommitted + new untracked files)
  scripts/agy-review.sh --staged        Review only staged changes
  scripts/agy-review.sh HEAD~3..HEAD    Review an explicit git range
  scripts/agy-review.sh -h | --help     Show this help

Environment:
  AGY_REVIEW_MODEL    Model (default: "Gemini 3.5 Flash (High)"; switch to
                      "Gemini 3.1 Pro (High)" when off the free tier).
  AGY_REVIEW_TIMEOUT  agy --print-timeout value (default: 240s).
  AGY_REVIEW_SANDBOX  1 (default) runs agy with --sandbox so it cannot run commands
                      or edit files (defends against prompt injection in the diff);
                      set 0 to let agy explore the repo for extra context.
  AGY_REVIEW_BASE     Base ref for the default range (default: origin/main).
  AGY_REVIEW_FETCH    1 (default) refreshes the base ref with a best-effort
                      'git fetch'; set 0 to skip (offline / slow remote).

Exit codes:
  0    review produced (or nothing to review)
  1    usage / environment error
  2    review FAILED (agy error or quota/rate-limit) — this is NOT a clean review
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
command -v agy >/dev/null 2>&1 || {
  echo "error: 'agy' (Antigravity CLI) not found on PATH" >&2
  exit 1
}
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "error: not inside a git repository" >&2
  exit 1
}

cd -- "$(git rev-parse --show-toplevel)"

# --- compute the diff --------------------------------------------------------
# Best-effort refresh so the comparison reflects the real remote; never fail or hang.
# GIT_TERMINAL_PROMPT=0 makes auth fail fast instead of prompting; AGY_REVIEW_FETCH=0
# skips the fetch entirely (offline / slow remote).
if [ "${AGY_REVIEW_FETCH:-1}" = "1" ]; then
  GIT_TERMINAL_PROMPT=0 git fetch --quiet origin 2>/dev/null || true
fi

if [ "$#" -gt 0 ]; then
  # Caller supplied explicit git-diff args/range (e.g. --staged, or A..B).
  RANGE_DESC="git diff $*"
  DIFF="$(git diff -U15 "$@")"
else
  # Default: everything not yet on origin/main — committed AND uncommitted.
  if ! git rev-parse --verify --quiet HEAD >/dev/null; then
    BASE=""
    RANGE_DESC="working tree (no commits yet)"
  elif git rev-parse --verify --quiet "$BASE_REF" >/dev/null &&
    BASE="$(git merge-base "$BASE_REF" HEAD 2>/dev/null)"; then
    RANGE_DESC="$BASE_REF → working tree (committed + uncommitted)"
  else
    BASE="$(git rev-parse HEAD)"
    RANGE_DESC="HEAD → working tree ($BASE_REF not found or unrelated; uncommitted only)"
  fi

  if [ -n "$BASE" ]; then
    DIFF="$(git diff -U15 "$BASE")"
  else
    DIFF=""
  fi

  # git diff ignores untracked files; append new files explicitly so they're reviewed.
  # -z + read -d '' handles paths with spaces / quotes / non-ASCII safely.
  while IFS= read -r -d '' f; do
    [ -n "$f" ] || continue
    DIFF+=$'\n'"$(git diff -U15 --no-index -- /dev/null "$f" 2>/dev/null || true)"
  done < <(git ls-files -z --others --exclude-standard)
fi

if ! grep -q '[^[:space:]]' <<<"$DIFF"; then
  echo "Nothing to review (empty diff for: $RANGE_DESC)."
  exit 0
fi

# Soft guard: very large diffs burn quota and may exceed context. Warn, then proceed.
diff_bytes=${#DIFF}
if [ "$diff_bytes" -gt 200000 ]; then
  echo "warning: large diff (~${diff_bytes} chars) — consider scoping with a range arg." >&2
fi

# --- build the prompt (lean & un-prescriptive: agy reviews with its own judgment) ---
read -r -d '' INSTRUCTIONS <<'PROMPT_EOF' || true
You are a senior software engineer performing a focused pre-push code review of this
repository. Review the diff below as a competent reviewer would, judging it on its
own merits — correctness, edge cases, security, concurrency, resource handling, and
test coverage as appropriate. The diff includes surrounding context lines; base your
review on it.

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

before="$(git status --porcelain)"

set +e
if [ "$SANDBOX" = "1" ]; then
  OUTPUT="$(printf '%s' "$PROMPT" | agy --sandbox --model "$MODEL" --print-timeout "$TIMEOUT" -p - 2>"$errfile")"
else
  OUTPUT="$(printf '%s' "$PROMPT" | agy --model "$MODEL" --print-timeout "$TIMEOUT" -p - 2>"$errfile")"
fi
status=$?
set -e
ERR="$(cat "$errfile")"

after="$(git status --porcelain)"

# --- user abort (Ctrl+C) -----------------------------------------------------
if [ "$status" -eq 130 ]; then
  echo "Review aborted by user." >&2
  exit 130
fi

# --- detect quota / auth / hard errors — never mistake these for a review ----
# Scan only the exit code and STDERR for error markers, never the review text itself
# (which could legitimately mention "quota", "429", etc. and trigger a false positive).
# Markers are error-state phrases, not bare words, so benign stderr (e.g. "rate limit:
# 59 remaining") won't trip them. NOTE: stderr is scanned even when status==0 because
# agy's exit code on a real quota error is not yet confirmed; revisit once observed.
if [ "$status" -ne 0 ] || printf '%s' "$ERR" | grep -qiE \
  'resource[_ ]?exhausted|rate[ _-]?limit(ed| exceeded| reached)|quota exceeded|exceeded your[^.]*quota|too many requests|\b429\b|unauthenticated|authentication failed|invalid api key'; then
  {
    echo "================ REVIEW FAILED (quota/error) ================"
    echo "agy exit status: $status   model: $MODEL"
    echo "--- stderr ---"
    printf '%s\n' "$ERR"
    if [ -n "$OUTPUT" ]; then
      echo "--- partial stdout ---"
      printf '%s\n' "$OUTPUT"
    fi
    echo "============================================================"
    echo "This is NOT a code review. On the free tier this is usually a quota/rate"
    echo "limit — retry later, or set AGY_REVIEW_MODEL to a lighter model."
  } >&2
  exit 2
fi

# --- read-only safety net: warn if agy mutated the working tree ---------------
if [ "$before" != "$after" ]; then
  echo "WARNING: the working tree changed during the review — agy may have modified" >&2
  echo "files despite the read-only mandate. Inspect with 'git status' / 'git diff'." >&2
fi

# --- emit the review ---------------------------------------------------------
echo "# Gemini review via agy — model: $MODEL — range: $RANGE_DESC"
echo
printf '%s\n' "$OUTPUT"
