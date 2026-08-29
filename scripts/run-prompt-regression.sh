#!/usr/bin/env bash
#
# run-prompt-regression.sh — manual prompt-regression sanity check (issues #102, #121).
#
# Runs PromptRegressionIT against a LIVE provider: the real LlmPriceExtractionService
# prompt is exercised over the labeled snippets in
# backend/src/test/resources/price-extraction/availability-cases.json, asserting the
# extracted availability/price/currency. This is a manual check, NOT a CI gate — models
# are not perfectly deterministic and it needs a reachable provider.
#
# Two providers are supported (#121): 'groq' (the production default) and 'ollama' (the
# local fallback). A prompt edit should be checked against both.
#
# The prompt itself lives only in LlmPriceExtractionService.java; this script holds
# no prompt text (single source of truth).
#
set -euo pipefail

DEFAULT_PROVIDER="groq"
DEFAULT_GROQ_MODEL="openai/gpt-oss-20b"
DEFAULT_OLLAMA_MODEL="qwen3:1.7b"

OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
OLLAMA_URL="${OLLAMA_URL%/}" # strip trailing slash so "${OLLAMA_URL}/api/tags" never doubles up
GROQ_URL="${GROQ_URL:-https://api.groq.com/openai/v1}"
GROQ_URL="${GROQ_URL%/}"

usage() {
  cat <<EOF
run-prompt-regression.sh — manual prompt-regression sanity check.

Drives PromptRegressionIT against a live provider and prints pass/fail per labeled case.

Usage:
  scripts/run-prompt-regression.sh [--provider groq|ollama] [MODEL]
  scripts/run-prompt-regression.sh -h | --help

Arguments:
  MODEL   Model to run the cases against. Default depends on the provider:
            groq   -> $DEFAULT_GROQ_MODEL   (use openai/gpt-oss-120b to spot-check the fulltext tier)
            ollama -> $DEFAULT_OLLAMA_MODEL (use qwen3.5:9b to spot-check the fulltext tier)
          Exported as PROMPT_REGRESSION_MODEL for the test.

Options:
  --provider  groq (default, matches production) or ollama (local fallback).

Environment:
  GROQ_API_KEY   Required for --provider groq.
  GROQ_URL       Groq base URL (default: https://api.groq.com/openai/v1). Used by the precheck AND
                 passed through to the suite, so both target the same endpoint.
  OLLAMA_URL     Ollama base URL (default: http://localhost:11434). Same pass-through.
  PROMPT_REGRESSION_PACE    Gap before each case, e.g. 9s (default 0s). Groq's free tier is 8,000
                            tokens/min and a call is ~1,100 tokens: unpaced, a run 429s after ~7
                            cases and leans on retries; 9s keeps it off the limiter entirely.
  PROMPT_REGRESSION_ONLY    Comma-separated case names to run (default: all).
  PROMPT_REGRESSION_REPEAT  Run each selected case N times (default 1). With ONLY, this is the
                            consistency check a quarantine decision needs: e.g. ONLY=notify_me
                            REPEAT=10 shows whether a case is reliably right or just right once.

Exit codes:
  0    suite ran and passed
  1    usage error, or provider unreachable / credential missing / model unavailable
  2    the regression suite reported failures
EOF
}

PROVIDER="$DEFAULT_PROVIDER"
MODEL=""

while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --provider)
      if [ $# -lt 2 ]; then
        echo "error: --provider needs a value (groq or ollama)." >&2
        exit 1
      fi
      PROVIDER="$2"
      shift 2
      ;;
    --provider=*)
      PROVIDER="${1#*=}"
      shift
      ;;
    -*)
      echo "error: unknown option '$1'. Run with --help." >&2
      exit 1
      ;;
    *)
      if [ -n "$MODEL" ]; then
        echo "error: unexpected extra argument '$1'. Run with --help." >&2
        exit 1
      fi
      MODEL="$1"
      shift
      ;;
  esac
done

# Escapes ERE metacharacters so a model name is matched literally (see the precheck comments below).
escape_ere() {
  printf '%s' "$1" | sed 's/[][\.^$*+?(){}|\\/]/\\&/g'
}

# --- precheck: fail fast rather than let the suite fail deep inside a test ------
case "$PROVIDER" in
  groq)
    MODEL="${MODEL:-$DEFAULT_GROQ_MODEL}"
    MODEL_RE="$(escape_ere "$MODEL")"
    if [ -z "${GROQ_API_KEY:-}" ]; then
      echo "error: GROQ_API_KEY is not set — export it, or use --provider ollama." >&2
      exit 1
    fi
    # The key goes in via curl's stdin config, never argv: process arguments are visible to any
    # other process on the machine (ps) and would land in shell history / CI logs.
    if ! models="$(printf 'header = "Authorization: Bearer %s"\n' "$GROQ_API_KEY" |
      curl -fsS --connect-timeout 5 --max-time 15 --config - "${GROQ_URL}/models" 2>/dev/null)"; then
      echo "error: Groq not reachable at ${GROQ_URL}, or GROQ_API_KEY was rejected." >&2
      exit 1
    fi
    # Match the model's "id" field specifically rather than the bare value anywhere in the JSON, so
    # an unrelated field can't produce a false match. The exact id is required by design — hence
    # MODEL_RE: model names contain regex metacharacters (the dot in "qwen3.5:9b", "openai/gpt-oss-20b"),
    # and interpolating them raw would quietly turn an exact match into a wildcard one.
    if ! printf '%s' "$models" | grep -qE "\"id\"[[:space:]]*:[[:space:]]*\"$MODEL_RE\""; then
      echo "error: model '$MODEL' is not available on this Groq account." >&2
      echo "       Check https://console.groq.com/docs/models for the current lineup." >&2
      exit 1
    fi
    PROVIDER_LABEL="Groq at ${GROQ_URL}"
    ;;
  ollama)
    MODEL="${MODEL:-$DEFAULT_OLLAMA_MODEL}"
    MODEL_RE="$(escape_ere "$MODEL")"
    if ! tags="$(curl -fsS --connect-timeout 5 --max-time 10 "${OLLAMA_URL}/api/tags" 2>/dev/null)"; then
      echo "error: Ollama not reachable at ${OLLAMA_URL} — start it with 'ollama serve'." >&2
      exit 1
    fi
    # Match the model's "name" field specifically (e.g. "name":"qwen3:1.7b") rather than the bare value
    # anywhere in the JSON, so an unrelated field can't produce a false match. ERE with optional
    # whitespace around the colon tolerates pretty-printed JSON ("name": "...") across Ollama versions.
    # The exact tag is required by design — target a specific model+tag, not a fuzzy base-name match.
    if ! printf '%s' "$tags" | grep -qE "\"name\"[[:space:]]*:[[:space:]]*\"$MODEL_RE\""; then
      echo "error: model '$MODEL' is not pulled. Run: ollama pull $MODEL" >&2
      exit 1
    fi
    PROVIDER_LABEL="Ollama at ${OLLAMA_URL}"
    ;;
  *)
    echo "error: --provider must be 'groq' or 'ollama', but was '$PROVIDER'." >&2
    exit 1
    ;;
esac

cd "$(git rev-parse --show-toplevel)/backend"

echo "Running prompt regression against model '$MODEL' (${PROVIDER_LABEL})..."
# -Dtest forces Surefire to include the *IT class (its default pattern only matches *Test).
# The env var is the runtime gate the test checks via @EnabledIfEnvironmentVariable.
# GROQ_URL/OLLAMA_URL are passed explicitly rather than left to inheritance: when the caller doesn't
# set them, the defaults above are plain (unexported) shell variables the forked JVM would never see,
# and the suite would silently fall back to the property value instead of the URL we just prechecked.
if RUN_PROMPT_REGRESSION=true PROMPT_REGRESSION_PROVIDER="$PROVIDER" PROMPT_REGRESSION_MODEL="$MODEL" \
  GROQ_URL="$GROQ_URL" OLLAMA_URL="$OLLAMA_URL" \
  ./mvnw -q test -Dtest=PromptRegressionIT; then
  echo "Prompt regression PASSED (provider=$PROVIDER model=$MODEL)."
else
  echo "Prompt regression FAILED (provider=$PROVIDER model=$MODEL) — see the per-case output above." >&2
  exit 2
fi
