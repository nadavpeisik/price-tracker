#!/usr/bin/env bash
#
# get-review-diff.sh — shared, deterministic diff-computation helper.
#
# Computes "the diff to review" the same way for every caller (agy-review.sh,
# codex-review.sh, ...) so multiple independent reviewers see the exact same input —
# a disagreement between them then reflects different judgment, not different scope.
# Pure git plumbing, no AI calls, read-only.
#
set -euo pipefail

REVIEW_BASE="${REVIEW_BASE:-origin/main}"
REVIEW_FETCH="${REVIEW_FETCH:-1}"
REVIEW_MAX_UNTRACKED_BYTES="${REVIEW_MAX_UNTRACKED_BYTES:-102400}"

usage() {
  cat <<'EOF'
get-review-diff.sh — shared diff-computation helper for review scripts.

Prints "# RANGE: <description>" followed by a blank line and the diff text (possibly
empty) to stdout. Pure git plumbing, no AI calls, read-only.

Usage:
  scripts/get-review-diff.sh                 Default scope: everything not yet on
                                             $REVIEW_BASE (committed + uncommitted +
                                             untracked)
  scripts/get-review-diff.sh --staged        Passthrough to git diff
  scripts/get-review-diff.sh HEAD~3..HEAD    Passthrough to git diff
  scripts/get-review-diff.sh -h | --help     Show this help

Note: the script runs from the repo root, so explicit range/path arguments are
resolved relative to the repo root (price-tracker/), not your current directory.

Environment:
  REVIEW_BASE   Base ref for the default range (default: origin/main).
  REVIEW_FETCH  1 (default) refreshes the base ref with a best-effort 'git fetch';
                set 0 to skip (offline / slow remote).
  REVIEW_MAX_UNTRACKED_BYTES
                Max size of an untracked file to include in full (default: 102400 =
                100 KiB). Larger untracked files are listed but their contents are
                skipped, to avoid bloating the diff.

Exit codes:
  0    ran successfully (diff may be empty — caller checks and reports "Nothing to
       review")
  1    usage / config error (bad ref, non-numeric byte limit, not a git repo). An
       invalid passthrough range may instead surface as git's own fatal exit status
       (e.g. 128) under 'set -e'. Either way, callers should treat any nonzero exit
       as "do not treat as a clean empty review."
EOF
}

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
esac

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "error: not inside a git repository" >&2
  exit 1
}

cd -- "$(git rev-parse --show-toplevel)"

# Best-effort refresh so the comparison reflects the real remote; never fail or hang.
# GIT_TERMINAL_PROMPT=0 makes auth fail fast instead of prompting; REVIEW_FETCH=0
# skips the fetch entirely (offline / slow remote).
if [ "$REVIEW_FETCH" = "1" ]; then
  if ! GIT_TERMINAL_PROMPT=0 git fetch --quiet origin 2>/dev/null; then
    echo "warning: 'git fetch origin' failed — review base ($REVIEW_BASE) may be stale" >&2
  fi
fi

if [ "$#" -gt 0 ]; then
  # Caller supplied explicit git-diff args/range (e.g. --staged, or A..B).
  RANGE_DESC="git diff $*"
  DIFF="$(git diff -U15 "$@")"
else
  # Default: everything not yet on $REVIEW_BASE — committed, uncommitted, and untracked.
  if ! git rev-parse --verify --quiet HEAD >/dev/null; then
    # No commits yet: diff against the empty tree so staged/tracked files are still
    # reviewed. git mktree writes + returns this repo's empty tree (hash-agnostic and
    # guaranteed to resolve; the well-known hardcoded SHA-1 empty tree does not resolve
    # in every repo).
    BASE="$(git mktree </dev/null)"
    RANGE_DESC="working tree (no commits yet)"
  elif git rev-parse --verify --quiet "$REVIEW_BASE" >/dev/null &&
    BASE="$(git merge-base "$REVIEW_BASE" HEAD 2>/dev/null)"; then
    RANGE_DESC="$REVIEW_BASE → working tree (committed + uncommitted + untracked)"
  else
    echo "error: cannot determine a review base — '$REVIEW_BASE' is missing locally or" >&2
    echo "       shares no history with HEAD. Run 'git fetch', set REVIEW_BASE to" >&2
    echo "       a valid ref, or pass an explicit range (e.g. HEAD~3..HEAD)." >&2
    exit 1
  fi

  if [ -n "$BASE" ]; then
    DIFF="$(git diff -U15 "$BASE")"
  else
    DIFF=""
  fi

  # git diff ignores untracked files; append new files explicitly so they're reviewed.
  # -z + read -d '' handles paths with spaces / quotes / non-ASCII safely.
  # Skip oversized untracked files (datasets, logs, build artifacts): dumping their
  # full contents would bloat the diff for no review value.
  case "$REVIEW_MAX_UNTRACKED_BYTES" in
    '' | *[!0-9]*)
      echo "error: REVIEW_MAX_UNTRACKED_BYTES must be a plain integer in bytes" >&2
      echo "       (no suffixes like K/KB); got '$REVIEW_MAX_UNTRACKED_BYTES'." >&2
      exit 1
      ;;
  esac
  while IFS= read -r -d '' f; do
    [ -n "$f" ] || continue
    if [ -f "$f" ]; then
      size="$(wc -c 2>/dev/null <"$f" || echo 0)" # || echo 0 keeps set -e from killing us
      size="${size//[[:space:]]/}"
      if [ -n "$size" ] && [ "$size" -gt "$REVIEW_MAX_UNTRACKED_BYTES" ]; then
        DIFF+=$'\n'"--- $f (skipped: large untracked file, ${size} bytes) ---"
        continue
      fi
    fi
    DIFF+=$'\n'"$(git diff -U15 --no-index -- /dev/null "$f" 2>/dev/null || true)"
  done < <(git ls-files -z --others --exclude-standard)
fi

echo "# RANGE: $RANGE_DESC"
echo
printf '%s\n' "$DIFF"
