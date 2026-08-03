#!/usr/bin/env bash
#
# agy-plan-review.sh — Local Gemini review (Google Antigravity CLI) of an IMPLEMENTATION PLAN.
#
# The sibling of agy-review.sh, one step earlier in the lifecycle: instead of reviewing the
# git diff before a commit, it reviews the *plan* before any code is written. Feeds a plan
# markdown file to a READ-ONLY Gemini reviewer and prints severity-tagged findings. Meant to
# be run during plan mode (by Claude Code or by hand) right before the plan is presented for
# approval. It NEVER edits, stages, commits, or pushes — it only reviews.
#
# Why this exists: catching a flawed approach at the plan stage is far cheaper than catching
# it in code review. The Gemini code-assist GitHub bot sunsets 2026-07-17; Antigravity's `agy`
# keeps that review quality available locally.
#
set -euo pipefail

MODEL="${AGY_REVIEW_MODEL:-Gemini 3.6 Flash (High)}"
TIMEOUT="${AGY_REVIEW_TIMEOUT:-240s}"
SANDBOX="${AGY_REVIEW_SANDBOX:-1}" # sandbox ON unless explicitly "0" (fail-safe); 0 = allow agy command/file access
PLAN_DIR="${AGY_PLAN_DIR:-$HOME/.claude/plans}"
PLAN_DIR="${PLAN_DIR/#\~/$HOME}" # expand a leading ~ if AGY_PLAN_DIR was set with a literal/quoted tilde

usage() {
  cat <<'EOF'
agy-plan-review.sh — Local Gemini review (Antigravity CLI) of an implementation plan.

Runs a READ-ONLY Gemini review of an implementation PLAN (not code) and prints the findings
to stdout. Never edits, stages, commits, or pushes.

Usage:
  scripts/agy-plan-review.sh PLAN.md       Review a specific plan file
  scripts/agy-plan-review.sh               Review the newest *.md in the plan dir
                                           (default: ~/.claude/plans)
  scripts/agy-plan-review.sh -             Review plan content read from stdin
  scripts/agy-plan-review.sh -h | --help   Show this help

Environment:
  AGY_REVIEW_MODEL    Model (default: "Gemini 3.6 Flash (High)"; switch to
                      "Gemini 3.1 Pro (High)" when off the free tier). Shared with
                      agy-review.sh so both tools use one set of dials.
  AGY_REVIEW_TIMEOUT  agy --print-timeout value (default: 240s).
  AGY_REVIEW_SANDBOX  On by default — runs agy with --sandbox so it cannot run commands or
                      edit files (defends against prompt injection in the plan). ONLY the
                      value 0 disables it (fail-safe: a typo can't silently turn off the
                      sandbox). Setting 0 lets agy read the codebase for extra context — more
                      useful here than for a diff review, since a plan is abstract and the
                      surrounding code sharpens the critique.
  AGY_PLAN_DIR        Directory scanned for the newest plan when no file is given
                      (default: ~/.claude/plans).

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

# Accept at most one positional arg (a plan file, or '-' for stdin). Reject extras so a
# stray argument isn't silently ignored.
if [ "$#" -gt 1 ]; then
  echo "error: too many arguments — pass at most one plan file (or '-' for stdin)." >&2
  echo "       run 'scripts/agy-plan-review.sh -h' for usage." >&2
  exit 1
fi

# --- preconditions -----------------------------------------------------------
command -v agy >/dev/null 2>&1 || {
  echo "error: 'agy' (Antigravity CLI) not found on PATH" >&2
  exit 1
}
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "error: not inside a git repository" >&2
  exit 1
}

# --- resolve the plan source (read BEFORE cd so relative paths resolve in CWD) ---
if [ "${1:-}" = "-" ]; then
  PLAN_DESC="stdin"
  PLAN="$(cat)"
elif [ "$#" -gt 0 ]; then
  plan_file="$1"
  [ -f "$plan_file" ] || {
    echo "error: plan file not found: $plan_file" >&2
    exit 1
  }
  PLAN_DESC="$plan_file"
  PLAN="$(cat -- "$plan_file")"
else
  # No arg: pick the most-recently-modified *.md in the plan dir (the plan just written).
  # Glob + -nt loop avoids parsing `ls` and the stat(1) mtime portability mess (BSD vs GNU).
  newest=""
  for f in "$PLAN_DIR"/*.md; do
    [ -f "$f" ] || continue # skip if glob didn't match (stays literal) or matched a non-file (e.g. a dir named *.md)
    if [ -z "$newest" ] || [ "$f" -nt "$newest" ]; then
      newest="$f"
    fi
  done
  [ -n "$newest" ] || {
    echo "error: no *.md plan files in $PLAN_DIR — pass a plan file path explicitly" >&2
    echo "       or set AGY_PLAN_DIR to the directory holding your plans." >&2
    exit 1
  }
  PLAN_DESC="$newest (newest in $PLAN_DIR)"
  PLAN="$(cat -- "$newest")"
  echo "Reviewing newest plan: $newest" >&2
fi

if ! grep -q '[^[:space:]]' <<<"$PLAN"; then
  echo "Nothing to review (empty plan: $PLAN_DESC)."
  exit 0
fi

# Soft guard: an unusually large plan burns quota and may exceed context. Warn, then proceed.
plan_bytes=${#PLAN}
if [ "$plan_bytes" -gt 200000 ]; then
  echo "warning: large plan (~${plan_bytes} chars) — consider trimming it." >&2
fi

cd -- "$(git rev-parse --show-toplevel)"

# --- build the prompt (lean & un-prescriptive: agy reviews with its own judgment) ---
read -r -d '' INSTRUCTIONS <<'PROMPT_EOF' || true
You are a senior software engineer and architect performing a focused review of an
IMPLEMENTATION PLAN for this repository. The plan proposes changes that have NOT been built
yet — you are reviewing the approach, not finished code. Judge it on its own merits: soundness
of the approach and architectural fit with the existing codebase, correctness and
completeness, missed edge cases and failure modes, security implications, concurrency /
transaction / resource-handling concerns, simpler or more robust alternatives, risky or
unstated assumptions, sequencing and ordering, and whether the testing / verification strategy
is adequate.

Do not write code or propose running commands; just report findings on the plan.

Output format:
- Group findings by severity: HIGH first, then MEDIUM, then LOW.
- For each finding: which part of the plan — the concern in one or two sentences — a concrete
  suggested change.
- If a severity level has no findings, write "none".
- Finish with a single line starting "VERDICT:" stating whether the plan is sound to implement
  as-is or needs revision (and the blockers if so).
PROMPT_EOF

PROMPT="$INSTRUCTIONS

=== IMPLEMENTATION PLAN (source: $PLAN_DESC) ===
$PLAN"

# --- run the review (read-only; prompt via argv — see PROMPT_MAX_BYTES below) -
errfile="$(mktemp)"
trap 'rm -f "$errfile"' EXIT

# Baseline of TRACKED files only (-uno): a read-only review must not mutate tracked files.
# Untracked files are excluded so unrelated concurrent churn (e.g. a co-running tool
# regenerating an untracked file mid-review) can't trip a false "read-only violation"; agy
# creating a brand-new file is already prevented by --sandbox. The `&& git diff` appends the
# actual tracked content, so re-editing an already-modified file (whose status flag alone
# wouldn't change) is still caught.
before="$(git status --porcelain -uno && git diff)"

# Hard ceiling on the prompt. agy 1.1.9 no longer reads the prompt from stdin (the old
# `-p -` form now sends the literal string "-"), so it goes in argv — which is bounded by
# ARG_MAX (1MB on macOS, shared with the environment). Measured with `wc -c`, NOT
# ${#PROMPT}: the latter counts characters, so a plan with multi-byte content would
# undercount and slip past this guard. Plans run far smaller than diffs (largest to date
# ~70KB), so this should never fire here — it stays for parity with agy-review.sh.
PROMPT_MAX_BYTES="${AGY_REVIEW_MAX_PROMPT_BYTES:-768000}" # 750 KiB, leaving argv+env headroom
prompt_bytes="$(printf '%s' "$PROMPT" | wc -c | tr -cd '0-9')"
if [ "$prompt_bytes" -gt "$PROMPT_MAX_BYTES" ]; then
  {
    echo "Prompt is ${prompt_bytes} bytes, over the ${PROMPT_MAX_BYTES}-byte argv limit."
    echo "Split the plan, or trim inlined code/log dumps out of it."
  } >&2
  exit 2
fi

# Build the agy args once; --sandbox is conditionally prepended (read-only by default).
# The array is never empty, so "${agy_args[@]}" is safe under set -u on bash 3.2.
# -p="$PROMPT" (bound form): agy's Go stdlib flag parser handles `-p "$PROMPT"` with a
# leading-dash prompt fine, but the bound form stays correct if agy ever moves to a
# parser that stops at unknown flags. --disable-slash-commands stops agy 1.1.9's print-
# mode slash/skill expansion from firing on command-like text inside the reviewed plan.
agy_args=(--model "$MODEL" --print-timeout "$TIMEOUT" --disable-slash-commands -p="$PROMPT")
if [ "$SANDBOX" != "0" ]; then
  agy_args=(--sandbox "${agy_args[@]}")
fi

set +e
OUTPUT="$(agy "${agy_args[@]}" 2>"$errfile")"
status=$?
set -e
ERR="$(cat "$errfile")"

after="$(git status --porcelain -uno && git diff)"

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
    echo "This is NOT a plan review, and retrying will NOT help."
    echo "Split the plan, or trim inlined code/log dumps out of it."
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
    echo "This is NOT a plan review. On the free tier this is usually a quota/rate"
    echo "limit — retry later, or set AGY_REVIEW_MODEL to a lighter model."
  } >&2
  exit 2
fi

# --- read-only safety net: fail if agy mutated the working tree ---------------
# A read-only review must never change files. If the tree changed, --sandbox failed to
# contain agy; treat it as a hard failure so a caller can't mistake a tampered run for a
# clean review and act on a mutated state.
if [ "$before" != "$after" ]; then
  {
    echo "============== REVIEW FAILED (read-only violation) =============="
    echo "The working tree changed during the review — agy modified files despite the"
    echo "read-only mandate (--sandbox), or a concurrent process edited tracked files"
    echo "mid-review. Inspect with 'git status' / 'git diff' and do NOT act on this until"
    echo "the cause is confirmed: this is NOT a valid review."
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
# nothing". This bit plan reviews specifically: plans live outside the workspace, so
# reading one needs read_file(~/.claude/plans) in ~/.gemini/antigravity-cli/settings.json
# (workspace files are auto-allowed). agy's stderr names the exact rule — surface it.
if [ -z "$(printf '%s' "$OUTPUT" | tr -d '[:space:]')" ]; then
  {
    echo "============== PLAN REVIEW FAILED (no output) =============="
    echo "agy exited $status but produced no review text."
    echo "--- stderr ---"
    printf '%s\n' "$ERR"
    echo "============================================================"
    echo "This is NOT a plan review. If stderr names an auto-denied read_file OUTSIDE the"
    echo "workspace (plans live in ~/.claude/plans), add that read_file rule to"
    echo "permissions.allow in ~/.gemini/antigravity-cli/settings.json. Never grant"
    echo "command(...) or write_file(...) to silence this — a reviewer that can run commands"
    echo "or edit files is no longer read-only. Any other denied tool: just re-run."
  } >&2
  exit 2
fi

echo "# Gemini plan review via agy — model: $MODEL — source: $PLAN_DESC"
echo
printf '%s\n' "$OUTPUT"
