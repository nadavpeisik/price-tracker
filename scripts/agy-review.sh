#!/usr/bin/env bash
#
# agy-review.sh — Local Gemini code review (Google Antigravity CLI) before pushing.
#
# Runs a READ-ONLY Gemini review of the changes you are about to push and prints the
# findings to stdout. Meant to be run on-demand from the pre-push workflow (by Claude
# Code or by hand). It NEVER edits, stages, commits, or pushes — it only reviews.
#
# Why this exists: the Gemini code-assist GitHub bot is gone (sunset 2026-07-17).
# Antigravity's `agy` keeps the same Gemini model family available locally, so we can get
# that review quality *before* the diff ever reaches GitHub.
#
set -euo pipefail

MODEL="${AGY_REVIEW_MODEL:-Gemini 3.6 Flash (High)}"
TIMEOUT="${AGY_REVIEW_TIMEOUT:-240s}"
SANDBOX="${AGY_REVIEW_SANDBOX:-1}" # sandbox ON unless explicitly "0" (fail-safe); 0 = allow agy command/file access
MAX_UNTRACKED_BYTES="${AGY_REVIEW_MAX_FILE_BYTES:-102400}" # bounds the SANDBOX=0 untracked-file snapshot below

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

Note: the script runs from the repo root, so explicit path arguments are resolved
relative to the repo root (price-tracker/), not your current directory.

Environment:
  AGY_REVIEW_MODEL    Model (default: "Gemini 3.6 Flash (High)"; switch to
                      "Gemini 3.1 Pro (High)" when off the free tier).
  AGY_REVIEW_TIMEOUT  agy --print-timeout value (default: 240s).
  AGY_REVIEW_SANDBOX  On by default — runs agy with --sandbox so it cannot run commands
                      or edit files (defends against prompt injection in the diff).
                      ONLY the value 0 disables it (fail-safe: a typo can't silently
                      turn off the sandbox); set 0 to let agy explore for extra context.
  AGY_REVIEW_BASE     Base ref for the default range (default: origin/main).
  AGY_REVIEW_FETCH    1 (default) refreshes the base ref with a best-effort
                      'git fetch'; set 0 to skip (offline / slow remote).
  AGY_REVIEW_MAX_FILE_BYTES
                      Max size of an untracked file to include in full (default:
                      102400 = 100 KiB). Larger untracked files are listed but their
                      contents are skipped, to avoid bloating the prompt / quota.
                      Also bounds the SANDBOX=0 safety-net snapshot below: untracked
                      files over this size are recorded by size instead of content
                      hash, so one large local artifact can't dominate the run.

Exit codes:
  0    review produced (or nothing to review)
  1    usage / environment error
  2    review FAILED (agy error, quota/rate-limit, oversized prompt, or agy
       returning no output) — this is NOT a clean review
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
# Delegates to get-review-diff.sh so every reviewer (agy, codex, ...) sees the exact
# same diff text. The env assignments must live inside the command substitution — a
# bare prefix has no command name, so bash would treat them as ordinary shell
# variables and never export them to get-review-diff.sh's process.
RAW="$(REVIEW_BASE="${AGY_REVIEW_BASE:-origin/main}" \
  REVIEW_FETCH="${AGY_REVIEW_FETCH:-1}" \
  REVIEW_MAX_UNTRACKED_BYTES="${AGY_REVIEW_MAX_FILE_BYTES:-102400}" \
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

# --- run the review (read-only; prompt via argv — see PROMPT_MAX_BYTES below) -
errfile="$(mktemp)"
trap 'rm -f "$errfile"' EXIT

# Snapshot tracked-file state (status + unstaged + staged diffs) so a read-only
# review never mutates tracked files. Untracked files are normally excluded (-uno):
# unrelated concurrent churn (e.g. a co-running tool regenerating an untracked file
# mid-review) can't trip a false "read-only violation", and the default --sandbox
# already prevents agy from creating new files. The `&& git diff` appends the actual
# tracked content, so re-editing an already-modified file (whose status flag alone
# wouldn't change) is still caught. `&& git diff --cached` does the same for staged
# content, so a sandbox-escape that only touches the index (e.g. a stray `git add`)
# is caught too.
#
# When AGY_REVIEW_SANDBOX=0 (explicit opt-out), agy CAN create/edit untracked files,
# which -uno alone would miss — so in that mode also record each untracked file's
# path + content hash. Files over MAX_UNTRACKED_BYTES (the same bound
# get-review-diff.sh uses for prompt content) are recorded by size instead of
# hashed, so one large local artifact can't make this snapshot dominate the run.
snapshot_state() {
  git status --porcelain -uno
  git diff
  git diff --cached
  if [ "$SANDBOX" = "0" ]; then
    git ls-files -z --others --exclude-standard |
      while IFS= read -r -d '' f; do
        [ -r "$f" ] || continue # skip files that are gone or unreadable (TOCTOU); also prevents set -e from killing us
        size="$(wc -c 2>/dev/null <"$f" || echo 0)"
        size="${size//[[:space:]]/}"
        if [ "$size" -gt "$MAX_UNTRACKED_BYTES" ]; then
          printf 'size:%s  %s\n' "$size" "$f"
        else
          hash="$(git hash-object -- "$f" 2>/dev/null || true)"
          [ -n "$hash" ] && printf '%s  %s\n' "$hash" "$f"
        fi
      done | sort
  fi
}

before="$(snapshot_state)"

# Hard ceiling on the prompt. agy 1.1.9 no longer reads the prompt from stdin (the old
# `-p -` form now sends the literal string "-"), so it goes in argv — which is bounded by
# ARG_MAX (1MB on macOS, shared with the environment). Measured with `wc -c`, NOT
# ${#PROMPT}: the latter counts characters, and a diff with multi-byte content (Hebrew
# shop pages, box-drawing chars) would undercount and slip past this guard.
PROMPT_MAX_BYTES="${AGY_REVIEW_MAX_PROMPT_BYTES:-768000}" # 750 KiB, leaving argv+env headroom
# A non-numeric override would make `[ -gt ]` error out, and a failing test inside `if`
# just skips the branch — the guard would silently not run. Reject it loudly instead.
case "$PROMPT_MAX_BYTES" in
  '' | *[!0-9]*)
    echo "error: AGY_REVIEW_MAX_PROMPT_BYTES must be a positive integer (got: $PROMPT_MAX_BYTES)" >&2
    exit 1
    ;;
esac
prompt_bytes="$(printf '%s' "$PROMPT" | wc -c | tr -cd '0-9')"
if [ "$prompt_bytes" -gt "$PROMPT_MAX_BYTES" ]; then
  {
    echo "Prompt is ${prompt_bytes} bytes, over the ${PROMPT_MAX_BYTES}-byte argv limit."
    echo "Narrow the review: use --staged, pass a smaller range, or exclude generated"
    echo "files (e.g. package-lock.json) from the commit under review."
  } >&2
  exit 2
fi

# Build the agy args once; --sandbox is conditionally prepended (read-only by default).
# The array is never empty, so "${agy_args[@]}" is safe under set -u on bash 3.2.
# -p="$PROMPT" (bound form): agy's Go stdlib flag parser handles `-p "$PROMPT"` with a
# leading-dash prompt fine, but the bound form stays correct if agy ever moves to a
# parser that stops at unknown flags. --disable-slash-commands stops agy 1.1.9's print-
# mode slash/skill expansion from firing on command-like text inside the reviewed diff.
agy_args=(--model "$MODEL" --print-timeout "$TIMEOUT" --disable-slash-commands -p="$PROMPT")
if [ "$SANDBOX" != "0" ]; then
  agy_args=(--sandbox "${agy_args[@]}")
fi

set +e
OUTPUT="$(agy "${agy_args[@]}" 2>"$errfile")"
status=$?
set -e
ERR="$(cat "$errfile")"

after="$(snapshot_state)"

# --- user abort (Ctrl+C) -----------------------------------------------------
if [ "$status" -eq 130 ]; then
  echo "Review aborted by user." >&2
  exit 130
fi

# --- argv overflow (E2BIG) — a size problem, never a quota problem ------------
# The preflight guard above should catch this first, but argv is also bounded by the
# environment size and by per-arg limits that differ across platforms (Linux caps a
# single argument near 128KiB), so exec can still fail here. Matched on stderr rather
# than the exit status: a bare 126 also means "permission denied", which would get the
# wrong advice. Checked BEFORE the quota branch so the misleading "retry later" hint
# below never fires for an overflow, where retrying is guaranteed to fail identically.
if printf '%s' "$ERR" | grep -qi 'argument list too long'; then
  {
    echo "============== REVIEW FAILED (prompt too large) =============="
    echo "agy never started: the prompt exceeded the OS argv limit (E2BIG)."
    echo "agy exit status: $status   prompt bytes: $prompt_bytes"
    echo "--- stderr ---"
    printf '%s\n' "$ERR"
    echo "============================================================="
    echo "This is NOT a code review, and retrying will NOT help. Narrow the review:"
    echo "use --staged, pass a smaller range, or exclude generated files."
  } >&2
  exit 2
fi

# --- detect quota / auth / hard errors — never mistake these for a review ----
# Scan only the exit code and STDERR for error markers, never the review text itself
# (which could legitimately mention "quota", "429", etc. and trigger a false positive).
# Markers are error-state phrases, not bare words, so benign stderr (e.g. "rate limit:
# 59 remaining") won't trip them. NOTE: stderr is scanned even when status==0 because
# agy's exit code on a real quota error is not yet confirmed; revisit once observed.
if [ "$status" -ne 0 ] || printf '%s' "$ERR" | grep -qiE \
  'resource[_ ]?exhausted|rate[ _-]?limit(ed| exceeded| reached)|quota exceeded|exceeded your[^.]*quota|too many requests|(^|[^0-9])429([^0-9]|$)|unauthenticated|authentication failed|invalid api key'; then
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

# --- read-only safety net: fail if agy mutated the working tree ---------------
# A read-only review must never change files. If the tree changed, --sandbox failed to
# contain agy; treat it as a hard failure so a caller can't mistake a tampered run for a
# clean review and commit from a mutated state.
if [ "$before" != "$after" ]; then
  {
    echo "============== REVIEW FAILED (read-only violation) =============="
    echo "The working tree changed during the review — agy modified files despite the"
    echo "read-only mandate (--sandbox), or a concurrent process edited tracked files"
    echo "mid-review. Inspect with 'git status' / 'git diff' and do NOT commit until the"
    echo "cause is confirmed: this is NOT a valid review."
    echo "================================================================"
  } >&2
  exit 2
fi

# --- emit the review ---------------------------------------------------------
# --- empty output is a FAILURE, never a clean review -------------------------
# agy >= 1.1.3 soft-denies any tool needing confirmation in headless mode (it cannot
# prompt), and when that happens it emits ZERO bytes on stdout and still exits 0 — the
# reason lands on stderr, which a status-0 run would otherwise discard. Without this
# guard the script prints its header and nothing else, which reads as "reviewed, found
# nothing". Files inside the workspace are auto-allowed; reads outside it (and commands)
# need an allow-rule in ~/.gemini/antigravity-cli/settings.json — agy's stderr names the
# exact rule, so surface it verbatim.
if [ -z "$(printf '%s' "$OUTPUT" | tr -d '[:space:]')" ]; then
  {
    echo "================ REVIEW FAILED (no output) =================="
    echo "agy exited $status but produced no review text."
    echo "--- stderr ---"
    printf '%s\n' "$ERR"
    echo "============================================================"
    echo "This is NOT a code review. If stderr names an auto-denied read_file OUTSIDE the"
    echo "workspace, add that read_file rule to permissions.allow in"
    echo "~/.gemini/antigravity-cli/settings.json. Never grant command(...) or write_file(...)"
    echo "to silence this — a reviewer that can run commands or edit files is no longer"
    echo "read-only. For any other denied tool, just re-run: what agy reaches for varies."
  } >&2
  exit 2
fi

echo "# Gemini review via agy — model: $MODEL — range: $RANGE_DESC"
echo
printf '%s\n' "$OUTPUT"
