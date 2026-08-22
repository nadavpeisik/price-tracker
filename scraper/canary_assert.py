"""Assertion helper for the E2E Canary workflow (.github/workflows/e2e-nightly.yml).

Reads a `/scrape` JSON response on stdin and decides whether the canary passes.

Contract (see issue #80):

* ``extractionSource == "blocked"`` is an EXPECTED, non-fatal outcome **when the reason
  names an anti-bot wall**. GitHub-hosted runners use Azure datacenter IPs, which
  Cloudflare's managed challenge walls by default — that is environmental, not a
  regression in our extraction logic. We emit a GitHub ``::warning::`` annotation and
  exit 0 so the canary does not go red overnight for a cause we cannot fix.
* Any OTHER blocked reason is fatal. Since #210 the scraper also reports a bare
  ``http-403`` and KSP-specific extraction failures as blocked; those used to fail the
  canary red by falling through to a non-structured tier, and must keep doing so rather
  than becoming green-with-warning.
* Any other response is held to the real-regression bar and exits non-zero on a miss:
  the scrape must report the expected tier (default ``structured`` — the canary hits the
  scraper directly, which only parses a price on the structured tier) AND carry a
  positive ``priceData.price``.
* Malformed / non-JSON stdin exits non-zero (defensive — a broken scraper response is a
  real failure, not something to swallow).
* A ``blocked`` response with no ``blockedReason`` is itself anomalous: the scraper's
  block detection always attaches a reason, so a reasonless block would mean the scraper
  regressed into labelling reachable pages as blocked — that fails, so we do not paper
  over a broken scraper with green-with-warnings.
"""

import argparse
import json
import math
import sys


def _warn(message: str) -> None:
    # GitHub reads workflow commands (::warning::) from stdout. Not print(): the
    # scraper's ruff config selects T20 (flake8-print).
    sys.stdout.write(f"::warning::{message}\n")


def _fail(message: str) -> int:
    sys.stderr.write(f"{message}\n")
    return 1


# Reasons naming an actual anti-bot wall — environmental on a datacenter runner IP (issue #80).
_ENVIRONMENTAL_REASON_PREFIXES = ("cloudflare-", "aws-waf-")


def evaluate(payload: dict, name: str, expect_source: str) -> int:
    """Return a process exit code (0 = pass) for one canary response."""
    source = payload.get("extractionSource")

    if source == "blocked":
        reason = payload.get("blockedReason")
        if not reason:
            return _fail(
                f"Canary {name} FAILED: 'blocked' with no blockedReason — anomalous "
                f"(scraper always attaches one), treating as a regression — payload={payload}"
            )
        if not isinstance(reason, str) or not reason.startswith(_ENVIRONMENTAL_REASON_PREFIXES):
            return _fail(
                f"Canary {name} FAILED: blocked with a non-bot-wall reason ({reason}) — "
                f"a real extraction failure, not an environmental block — payload={payload}"
            )
        _warn(f"Canary {name}: blocked by bot-wall ({reason}) — environmental, not a regression")
        return 0

    if source != expect_source:
        return _fail(
            f"Canary {name} FAILED: expected extractionSource={expect_source!r}, "
            f"got {source!r} — payload={payload}"
        )

    price_data = payload.get("priceData")
    if not isinstance(price_data, dict):
        return _fail(
            f"Canary {name} FAILED: extractionSource={source!r} but priceData is "
            f"missing — payload={payload}"
        )

    # bool is a subclass of int (True == 1), and NaN/Inf are floats that dodge `<= 0`
    # (every NaN comparison is False; inf is not <= 0) — exclude both so this means a
    # genuine, finite, positive number.
    price = price_data.get("price")
    if (
        isinstance(price, bool)
        or not isinstance(price, int | float)
        or not math.isfinite(price)
        or price <= 0
    ):
        return _fail(f"Canary {name} FAILED: non-positive price {price!r} — payload={payload}")

    sys.stdout.write(f"Canary {name}: OK (source={source}, price={price})\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="E2E canary scrape-response check.")
    parser.add_argument("--name", required=True, help="Canary site label for messages.")
    parser.add_argument(
        "--expect-source",
        default="structured",
        help="Required extractionSource when the site is not blocked.",
    )
    args = parser.parse_args(argv)

    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        return _fail(f"Canary {args.name} FAILED: invalid JSON ({exc}) — raw={raw!r}")

    if not isinstance(payload, dict):
        return _fail(f"Canary {args.name} FAILED: expected a JSON object — got {payload!r}")

    return evaluate(payload, args.name, args.expect_source)


if __name__ == "__main__":
    sys.exit(main())
