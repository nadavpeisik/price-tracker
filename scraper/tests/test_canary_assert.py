"""Unit tests for canary_assert.py (the E2E Canary scrape-response check, issue #80)."""

import io
import json

import pytest

import canary_assert


def test_blocked_is_non_fatal_and_warns(capsys):
    payload = {
        "extractionSource": "blocked",
        "priceData": None,
        "blockedReason": "cloudflare-managed:cf-ray=abc-IAD",
    }
    assert canary_assert.evaluate(payload, "string6", "structured") == 0
    out = capsys.readouterr().out
    assert out.startswith("::warning::")
    assert "string6" in out
    assert "cloudflare-managed:cf-ray=abc-IAD" in out


@pytest.mark.parametrize(
    "payload",
    [
        {"extractionSource": "blocked"},
        {"extractionSource": "blocked", "blockedReason": ""},
    ],
)
def test_blocked_without_reason_fails(payload):
    # The scraper always attaches a reason; a reasonless block means it regressed into
    # labelling reachable pages as blocked — fail rather than green-with-warnings.
    assert canary_assert.evaluate(payload, "string6", "structured") == 1


def test_structured_with_positive_price_passes():
    payload = {"extractionSource": "structured", "priceData": {"price": 1234.5}}
    assert canary_assert.evaluate(payload, "thomann", "structured") == 0


def test_wrong_tier_fails():
    payload = {"extractionSource": "fulltext", "priceData": {"price": 99}}
    assert canary_assert.evaluate(payload, "string6", "structured") == 1


def test_missing_price_data_fails():
    payload = {"extractionSource": "structured", "priceData": None}
    assert canary_assert.evaluate(payload, "string6", "structured") == 1


@pytest.mark.parametrize(
    "price",
    [0, -5, "12.0", None, True, False, float("nan"), float("inf"), float("-inf")],
)
def test_non_positive_or_non_numeric_price_fails(price):
    # bool (subclass of int) and non-finite floats must NOT count as a valid price.
    payload = {"extractionSource": "structured", "priceData": {"price": price}}
    assert canary_assert.evaluate(payload, "string6", "structured") == 1


def _run_main(monkeypatch, raw: str, argv: list[str]) -> int:
    monkeypatch.setattr("sys.stdin", io.StringIO(raw))
    return canary_assert.main(argv)


def test_main_malformed_json_fails(monkeypatch):
    assert _run_main(monkeypatch, "not json", ["--name", "string6"]) == 1


def test_main_non_object_json_fails(monkeypatch):
    assert _run_main(monkeypatch, "[1, 2, 3]", ["--name", "string6"]) == 1


def test_main_blocked_passes_end_to_end(monkeypatch):
    raw = json.dumps({"extractionSource": "blocked", "blockedReason": "x"})
    assert _run_main(monkeypatch, raw, ["--name", "string6"]) == 0


def test_main_defaults_expect_source_to_structured(monkeypatch):
    raw = json.dumps({"extractionSource": "structured", "priceData": {"price": 10}})
    assert _run_main(monkeypatch, raw, ["--name", "thomann"]) == 0


def test_main_honors_explicit_expect_source(monkeypatch):
    # A snippet response fails under the default (structured) but passes when the
    # caller explicitly expects snippet — proving the flag is actually wired through.
    raw = json.dumps({"extractionSource": "snippet", "priceData": {"price": 10}})
    assert _run_main(monkeypatch, raw, ["--name", "x"]) == 1
    assert _run_main(monkeypatch, raw, ["--name", "x", "--expect-source", "snippet"]) == 0
