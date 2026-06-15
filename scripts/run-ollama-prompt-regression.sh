#!/usr/bin/env bash
#
# run-ollama-prompt-regression.sh — manual prompt-regression sanity check (issue #102).
#
# Runs OllamaPromptRegressionIT against a LIVE local Ollama: the real
# OllamaPriceExtractionService prompt is exercised over the labeled snippets in
# backend/src/test/resources/price-extraction/availability-cases.json, asserting the
# extracted `available` flag. This is a manual check, NOT a CI gate — small models are
# not perfectly deterministic and it needs Ollama running.
#
# The prompt itself lives only in OllamaPriceExtractionService.java; this script holds
# no prompt text (single source of truth).
#
set -euo pipefail

DEFAULT_MODEL="qwen3:1.7b"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
OLLAMA_URL="${OLLAMA_URL%/}" # strip trailing slash so "${OLLAMA_URL}/api/tags" never doubles up

usage() {
  cat <<EOF
run-ollama-prompt-regression.sh — manual Ollama prompt-regression sanity check.

Drives OllamaPromptRegressionIT against a live local Ollama and prints pass/fail per
labeled case. Requires 'ollama serve' running with the target model pulled.

Usage:
  scripts/run-ollama-prompt-regression.sh [MODEL]
  scripts/run-ollama-prompt-regression.sh -h | --help

Arguments:
  MODEL   Ollama model to run the cases against (default: $DEFAULT_MODEL). Exported as
          OLLAMA_REGRESSION_MODEL for the test. Use e.g. 'qwen3.5:9b' to spot-check the
          fulltext model.

Environment:
  OLLAMA_URL   Base URL for the reachability precheck (default: http://localhost:11434).

Exit codes:
  0    suite ran and passed
  1    usage error, or Ollama unreachable / target model not pulled
  2    the regression suite reported failures
EOF
}

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
esac

MODEL="${1:-$DEFAULT_MODEL}"

# --- precheck: fail fast rather than let the test silently pull models ---------
if ! tags="$(curl -fsS --connect-timeout 5 --max-time 10 "${OLLAMA_URL}/api/tags" 2>/dev/null)"; then
  echo "error: Ollama not reachable at ${OLLAMA_URL} — start it with 'ollama serve'." >&2
  exit 1
fi
# Match the model's "name" field specifically (e.g. "name":"qwen3:1.7b") rather than the bare value
# anywhere in the JSON, so an unrelated field can't produce a false match. ERE with optional
# whitespace around the colon tolerates pretty-printed JSON ("name": "...") across Ollama versions.
# The exact tag is required by design — target a specific model+tag, not a fuzzy base-name match.
if ! printf '%s' "$tags" | grep -qE "\"name\"[[:space:]]*:[[:space:]]*\"$MODEL\""; then
  echo "error: model '$MODEL' is not pulled. Run: ollama pull $MODEL" >&2
  exit 1
fi

cd "$(git rev-parse --show-toplevel)/backend"

echo "Running prompt regression against model '$MODEL' (Ollama at ${OLLAMA_URL})..."
# -Dtest forces Surefire to include the *IT class (its default pattern only matches *Test).
# The env var is the runtime gate the test checks via @EnabledIfEnvironmentVariable.
if RUN_OLLAMA_PROMPT_REGRESSION=true OLLAMA_REGRESSION_MODEL="$MODEL" \
  ./mvnw -q test -Dtest=OllamaPromptRegressionIT; then
  echo "Prompt regression PASSED (model=$MODEL)."
else
  echo "Prompt regression FAILED (model=$MODEL) — see the per-case output above." >&2
  exit 2
fi
