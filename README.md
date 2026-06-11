# PriceHunt

[![CI](https://github.com/nadavpeisik/price-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/nadavpeisik/price-tracker/actions/workflows/ci.yml)
[![E2E Canary](https://github.com/nadavpeisik/price-tracker/actions/workflows/e2e-nightly.yml/badge.svg)](https://github.com/nadavpeisik/price-tracker/actions/workflows/e2e-nightly.yml)

A self-hosted price tracker for e-commerce products. Submit a product URL, get a structured price extraction without writing a custom scraper per site.

## What it does

Send a URL to the backend. It scrapes the page in a headless browser, then runs the HTML through a tiered extraction pipeline:

1. **Structured data** — JSON-LD / microdata / Schema.org pricing. No LLM needed.
2. **CSS / meta selectors** — common patterns (`meta[itemprop=price]`, `[data-price]`, etc.) reduced to a small snippet, then parsed by a local LLM.
3. **Regex-filtered fulltext** — the pruned `innerText` is line-filtered down to ~2k chars and handed to the LLM as a last resort.

Each tier short-circuits on success. In practice, mainstream retailers (Thomann, WooCommerce, Shopify) hit tier 1 and never touch the LLM — keeping cost near zero and latency to a single page render. Tiers 2 and 3 exist as fallbacks for long-tail sites that ship no structured data.

Every successful extraction is appended as an immutable `PriceRecord`, building a per-URL price history queryable via the API.

## Architecture

```
                            ┌──────────────────────┐
                            │  Spring Boot (8080)  │
   POST /api/products  ───▶ │                      │ ─── JPA ──▶  PostgreSQL
                            │  ProductController   │
                            │  TrackingService     │
                            │  PriceExtraction     │
                            └──────┬────────┬──────┘
                                   │        │
                          HTTP     │        │   Spring AI
                                   ▼        ▼
                  ┌────────────────────┐  ┌─────────────────────┐
                  │ Python Scraper     │  │ Ollama (11434)      │
                  │ FastAPI+Playwright │  │ local, you run it   │
                  │ port 8001          │  └─────────────────────┘
                  └────────────────────┘
```

### Extraction waterfall

```
Pre-step            — DOM pruning in scraper   → strips nav/footer/ads/scripts
Tier 1 (STRUCTURED) — JSON-LD / Schema.org     → returns structured PriceData; no LLM
Tier 2 (SNIPPET)    — CSS/meta selectors       → ~100–500 char snippet; LLM called
Tier 3 (FULLTEXT)   — Regex line-filter        → ~2000 char text; LLM called
```

The scraper response carries an `extractionSource` enum (`STRUCTURED | SNIPPET | FULLTEXT`) so the backend knows which path was taken without inspecting content.

## Layout

```
price-tracker/
├── backend/          Spring Boot 4 — REST API, JPA, price-extraction orchestration
├── scraper/          Python FastAPI + Playwright — DOM pruning + tier 1/2 extraction
├── compose.yaml      Docker Compose — postgres + scraper (auto-started by Spring Boot)
└── .github/workflows/
    ├── ci.yml          Backend + scraper tests on every PR
    └── e2e-nightly.yml Canary scrapes against real sites, nightly + on-demand
```

## Quickstart

Prerequisites: Java 21, Docker, [Ollama](https://ollama.com/) running locally with a model pulled (e.g. `llama3.2`).

```bash
git clone git@github.com:nadavpeisik/price-tracker.git
cd price-tracker

# Postgres credentials — required (compose.yaml refuses to start without them)
cp .env.example .env

# Run Ollama in a separate terminal
ollama serve
ollama pull llama3.2

# Start the backend — Spring Boot auto-starts postgres + scraper via Docker Compose
cd backend
./mvnw spring-boot:run
```

Verify it's up:

```bash
# Create a product
curl -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Duesenberg Starplayer TV"}'
# → 201 { "id": 1, ... }

# Attach a URL to track
curl -X POST http://localhost:8080/api/products/1/track \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.thomannmusic.com/duesenberg_starplayer_tv_blue_sparkle.htm","shopName":"Thomann"}'
# → 200 with extracted price and current PriceRecord
```

## API

Base path: `/api/products`

| Method | Path | Description |
|---|---|---|
| `POST` | `/` | Create a product |
| `GET` | `/` | List products (paginated; `?page`, `?size`, `?sort`) |
| `GET` | `/{id}` | Get product detail with tracked items |
| `PATCH` | `/{id}` | Update product fields |
| `DELETE` | `/{id}` | Delete a product and all its tracked items |
| `POST` | `/{id}/track` | Attach a URL to a product and run an initial scrape |
| `POST` | `/{id}/tracked-items/{itemId}/refresh` | Re-scrape a tracked URL and append a new PriceRecord |
| `PATCH` | `/{id}/tracked-items/{itemId}` | Update a tracked item (e.g. shop name) |
| `DELETE` | `/{id}/tracked-items/{itemId}` | Remove a tracked URL |
| `GET` | `/{id}/tracked-items/{itemId}/price-history` | Price history with optional `?from`/`?to` ISO timestamps |

## Tech stack

- **Backend:** Java 21, Spring Boot 4.0.3, Spring AI 2.0.0-M2, Spring Data JPA, Lombok
- **Scraper:** Python 3.12, FastAPI 0.135, Playwright 1.58 (chromium), pytest
- **Storage:** PostgreSQL 17
- **LLM:** Ollama (local) — model is configurable; tier 1 keeps most requests off the LLM entirely
- **Orchestration:** Docker Compose (postgres + scraper); Spring Boot's `spring-boot-docker-compose` starts them on `./mvnw spring-boot:run`
- **CI:** GitHub Actions

## Status & roadmap

- [x] **Phase 1** — Synchronous HTTP pipeline: URL in → price in Postgres.
- [x] **Phase 1.5** — Extraction waterfall: DOM pruning + structured/selector/regex tiers. Eliminates LLM calls for most major retailers.
- [x] **CRUD API** — Full product + tracked-item lifecycle, paginated listing, price-history with date filtering.
- [x] **CI** — Backend + scraper tests on every PR; nightly canary against real sites (bot-wall blocks tolerated as warnings, not failures).
- [ ] **Phase 1.6** — Selector caching: when the LLM fires, store the CSS selector on the tracked item; future checks skip the waterfall and self-heal if the selector stops returning data.
- [ ] **Phase 1.7** — SSRF hardening: reject private IP ranges and cloud metadata endpoints at the user-input boundary before dispatching scrape requests.
- [ ] **Phase 2** — Async Kafka pipeline; replace the synchronous scraper call with `ScrapeRequested` / `ScrapeCompleted` events. `POST /track` returns `202 Accepted` immediately.
- [ ] **Phase 3** — Price-change detection, `PriceDroppedEvent`, notifications.

## Testing

```bash
# Backend — uses @DataJpaTest with H2 at test scope, no Postgres required
cd backend && ./mvnw test

# Scraper
cd scraper
pip install -e '.[dev]'
playwright install --with-deps chromium
pytest -v
```

CI runs both suites on every pull request and push to `main` (`.github/workflows/ci.yml`). A separate nightly workflow (`.github/workflows/e2e-nightly.yml`) hits live URLs at Thomann, string6, and Wild Guitars and asserts that extraction still resolves to the structured tier — catching upstream HTML drift before it becomes a production surprise. A Cloudflare/bot-wall `blocked` response is treated as an informational `::warning::` rather than a failure: the runner's datacenter IP gets walled by default, which is environmental, not a regression in our extraction logic (the shared check lives in `scraper/canary_assert.py`, unit-tested in CI).

## License

No license declared yet. All rights reserved by the author until one is added.
