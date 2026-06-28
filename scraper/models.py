"""Shared wire DTOs for the scraper.

Extracted from main.py so site-specific handlers (e.g. sites/ksp.py) can return a full
ScrapeResponse without importing main — main imports the handlers, so a handler importing main
back would be a circular import. main.py re-exports these names, so existing `from main import ...`
consumers (tests) keep working unchanged.
"""

from enum import Enum

from pydantic import BaseModel, field_validator


class ExtractionSource(str, Enum):
    STRUCTURED = "structured"
    SNIPPET = "snippet"
    FULLTEXT = "fulltext"
    BLOCKED = "blocked"


# Tri-state: "can you get it" (pre-order/back-order/online-only count as available), not "on a
# shelf". UNKNOWN is a real third state — a page with no availability signal is unknown, not
# out of stock. Lowercase wire values like ExtractionSource; the backend's
# accept-case-insensitive-enums maps them to the Java AvailabilityStatus.
class AvailabilityStatus(str, Enum):
    AVAILABLE = "available"
    UNAVAILABLE = "unavailable"
    UNKNOWN = "unknown"


class ScrapeRequest(BaseModel):
    url: str


class PriceData(BaseModel):
    price: float
    currency: str
    availability: AvailabilityStatus = AvailabilityStatus.UNKNOWN

    @field_validator("availability", mode="before")
    @classmethod
    def _coerce_availability(cls, v):
        # The JS normalizer emits a canonical token, but be defensive: an unrecognized /
        # blank / None value becomes UNKNOWN rather than raising a ValidationError that
        # would 500 the scrape.
        if isinstance(v, AvailabilityStatus):
            return v
        if v is None:
            return AvailabilityStatus.UNKNOWN
        try:
            return AvailabilityStatus(str(v).strip().lower())
        except ValueError:
            return AvailabilityStatus.UNKNOWN


class ShopNameProposal(BaseModel):
    # The scraper's proposed shop name and how confident the signal is: strong = a site-level
    # signal (og:site_name / JSON-LD Organization), weak = a <title> guess. A proposal, not the
    # final name — the backend resolver decides the stored name (a curated/learned mapping can
    # override even a strong proposal). See _SITE_NAME_SCRIPT.
    name: str
    strong: bool


class ScrapeResponse(BaseModel):
    extractionSource: ExtractionSource
    priceData: PriceData | None = None
    snippet: str | None = None
    innerText: str | None = None
    blockedReason: str | None = None
    shopNameProposal: ShopNameProposal | None = None
