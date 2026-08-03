#!/usr/bin/env bash
#
# codex-plan-review.sh — Local Codex review of an IMPLEMENTATION PLAN.
#
# Sibling of agy-plan-review.sh and codex-review.sh: a second, independent
# plan reviewer per issue #81. Feeds a plan markdown file to a READ-ONLY Codex
# reviewer and prints severity-tagged findings. Meant to be run during plan mode
# (by Claude Code or by hand) right before the plan is presented for approval. It
# NEVER edits, stages, commits, or pushes — it only reviews.
#
# Codex has no native "review this plan" subcommand (`codex exec review` is
# scoped to git diffs), so this uses plain `codex exec --sandbox read-only -`
# (stdin) — the direct analog of agy's `agy --sandbox -p -`.
#
set -euo pipefail

MODEL="${CODEX_REVIEW_MODEL-gpt-5.6-sol}"          # shared with codex-review.sh; pinned, set to "" for Codex's own default
REASONING="${CODEX_REVIEW_REASONING_EFFORT:-high}" # shared with codex-review.sh
TIMEOUT="${CODEX_REVIEW_TIMEOUT:-240s}"            # shared with codex-review.sh
# Falls back to AGY_PLAN_DIR if set — both tools read the same plan directory by
# default, so one override (AGY_PLAN_DIR) covers both unless they need to diverge.
PLAN_DIR="${CODEX_PLAN_DIR:-${AGY_PLAN_DIR:-$HOME/.claude/plans}}"
# Expand a leading ~ if PLAN_DIR was set with a literal/quoted tilde. Only bare "~"
# and "~/..." are handled (this user's home) — "~otheruser/..." is left as-is rather
# than mangled into $HOME's parent.
case "$PLAN_DIR" in
  "~") PLAN_DIR="$HOME" ;;
  "~/"*) PLAN_DIR="$HOME/${PLAN_DIR#"~/"}" ;;
esac

usage() {
  cat <<'EOF'
codex-plan-review.sh — Local Codex review of an implementation plan.

Runs a READ-ONLY Codex review of an implementation PLAN (not code) and prints the
findings to stdout. Never edits, stages, commits, or pushes.

Usage:
  scripts/codex-plan-review.sh PLAN.md       Review a specific plan file
  scripts/codex-plan-review.sh               Review the newest *.md in the plan dir
                                             (default: ~/.claude/plans)
  scripts/codex-plan-review.sh -             Review plan content read from stdin
  scripts/codex-plan-review.sh -h | --help   Show this help

Findings are grouped HIGH/MEDIUM/LOW (matching agy-plan-review.sh's format — this is
our custom prompt, not Codex's native review skill, which only applies to
`codex exec review`'s git-diff mode), ending with a 'VERDICT:' line.

Environment:
  CODEX_REVIEW_MODEL  Model passed as `-m <model>` (default: gpt-5.6-sol — pinned so
                      reviews don't drift with ~/.codex/config.toml). Set to the empty
                      string for Codex's own default. Shared with codex-review.sh.
  CODEX_REVIEW_REASONING_EFFORT
                      Passed as `-c model_reasoning_effort="<value>"` (default:
                      high). Shared with codex-review.sh.
  CODEX_REVIEW_TIMEOUT
                      Best-effort wall-clock timeout for the codex call (default:
                      240s). Wraps the call with `timeout`/`gtimeout` if either is on
                      PATH (a `timeout` that isn't GNU coreutils, e.g. Windows'
                      timeout.exe, is ignored); otherwise no timeout is enforced.
                      Shared with codex-review.sh.
  CODEX_PLAN_DIR      Directory scanned for the newest plan when no file is given
                      (default: $AGY_PLAN_DIR if set, else ~/.claude/plans).

Always runs `codex exec --sandbox read-only` — unlike agy's all-or-nothing
AGY_REVIEW_SANDBOX, this blocks all writes while still letting Codex read the
codebase (including AGENTS.md, auto-loaded as context) for a grounded critique.
No sandbox toggle is offered.

Exit codes:
  0    review produced (or nothing to review)
  1    usage / environment error
  2    review FAILED (codex error, timeout, quota/rate-limit, or no output) — this is NOT a
       plan review
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
  echo "       run 'scripts/codex-plan-review.sh -h' for usage." >&2
  exit 1
fi

# --- preconditions -----------------------------------------------------------
command -v codex >/dev/null 2>&1 || {
  echo "error: 'codex' (Codex CLI) not found on PATH" >&2
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
    echo "       or set CODEX_PLAN_DIR (or AGY_PLAN_DIR) to the directory holding your plans." >&2
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

# --- build the prompt (lean & un-prescriptive: codex reviews with its own judgment) ---
read -r -d '' INSTRUCTIONS <<'PROMPT_EOF' || true
You are a senior software engineer and architect performing a focused review of an
IMPLEMENTATION PLAN for this repository. The plan proposes changes that have NOT been
built yet — you are reviewing the approach, not finished code. Judge it on its own
merits: soundness of the approach and architectural fit with the existing codebase,
correctness and completeness, missed edge cases and failure modes, security
implications, concurrency / transaction / resource-handling concerns, simpler or more
robust alternatives, risky or unstated assumptions, sequencing and ordering, and
whether the testing / verification strategy is adequate.

This repository's AGENTS.md describes its conventions and architecture and has
already been loaded as project context. You are running with read-only filesystem
access — use it to read relevant source files and run git commands (log, diff, grep,
etc.) as needed to ground your critique in the actual codebase.

Do not write code or propose running mutating commands; just report findings on the
plan.

Output format:
- Group findings by severity: HIGH first, then MEDIUM, then LOW.
- For each finding: which part of the plan — the concern in one or two sentences — a
  concrete suggested change.
- If a severity level has no findings, write "none".
- Finish with a single line starting "VERDICT:" stating whether the plan is sound to
  implement as-is or needs revision (and the blockers if so).
PROMPT_EOF

PROMPT="$INSTRUCTIONS

=== IMPLEMENTATION PLAN (source: $PLAN_DESC) ===
$PLAN"

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
    echo "trim the plan."
    echo "========================================================================"
    echo "This is NOT a plan review."
  } >&2
  exit 2
fi

# --- detect quota / auth / hard errors — never mistake these for a review ------
# codex exec echoes the ENTIRE session transcript to stderr, including the prompt we
# sent — the plan under review — between bare "user"/"codex" marker lines. A plan can
# legitimately contain phrases this regex matches (e.g. this very plan, which
# documents this regex's source verbatim) — scanning the full transcript would
# self-match on that echoed text. Restrict the scan to the session preamble
# (everything before the first bare "user" line), where genuine CLI-level errors
# (auth/rate-limit before or instead of a session) appear. If $ERR has no "user" line
# (e.g. codex failed before starting a session), the whole of $ERR is the preamble.
# Mirrors the fix applied to codex-review.sh after a live false positive there.
# NOTE: exact wording for ChatGPT-plan auth quota/rate-limit errors is not yet
# confirmed; revisit once observed.
# On Windows, codex's stderr may use CRLF line endings — strip \r with bash
# parameter expansion (no subprocess) before the here-string so /^user$/
# still matches a "user\r" marker line without introducing a tr|awk pipeline
# that would SIGPIPE when awk exits early on a long transcript.
ERR_PREAMBLE="$(awk '/^user$/{exit} {print}' <<<"${ERR//$'\r'/}")"
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
    echo "This is NOT a plan review."
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
    echo "Inspect with 'git status' / 'git diff' and do NOT act on this until the cause"
    echo "is confirmed: this is NOT a valid review."
    echo "================================================================"
  } >&2
  exit 2
fi

# --- empty output is a FAILURE, never a clean review -------------------------
# No known Codex failure mode produces this (unlike agy, which soft-denies tools in
# headless mode), but the invariant is the same for every reviewer: a header with no
# findings under it must never be readable as "reviewed, found nothing".
if [ -z "$(printf '%s' "$OUTPUT" | tr -d '[:space:]')" ]; then
  {
    echo "============== PLAN REVIEW FAILED (no output) =============="
    echo "codex exited $status but produced no review text."
    echo "--- stderr ---"
    printf '%s\n' "$ERR"
    echo "============================================================"
    echo "This is NOT a plan review — re-run before trusting it."
  } >&2
  exit 2
fi

# --- emit the review -----------------------------------------------------------
echo "# Codex plan review — model: ${MODEL:-default} — reasoning: $REASONING — source: $PLAN_DESC"
echo
printf '%s\n' "$OUTPUT"
