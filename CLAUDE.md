# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo Structure

This is a monorepo. The git root is `price-tracker/`, one level above this directory.

```
price-tracker/
├── compose.yaml      ← orchestrates all services (postgres, ollama, scraper)
├── backend/          ← Spring Boot (this directory)
└── scraper/          ← Python FastAPI + Playwright scraper
```

## Build & Run Commands

```bash
# From backend/
./mvnw spring-boot:run        # Run the application — auto-starts Docker Compose services
./mvnw clean install          # Build and install
./mvnw test                   # Run all tests
./mvnw test -Dtest=ClassName  # Run a single test class
./mvnw package                # Create JAR
```

`compose.yaml` lives at the repo root (`../compose.yaml`). Spring Boot finds it via `spring.docker.compose.file=../compose.yaml` in `application.properties`. It spins up PostgreSQL (5432), Ollama (11434), and the Python scraper (8001) automatically — no manual `docker-compose up` needed.

## Architecture

**Layered architecture:** `Controller → Service → Repository → Domain`

**Base package:** `com.np.pricehunt.backend`

**Flow for the main use case (track a product URL):**
1. `POST /api/products/track` hits `ProductController`
2. `ProductTrackingService.trackNewUrl()` orchestrates the workflow (transactional):
   - Calls `ScraperClient` → `POST http://localhost:8001/scrape` → Python Playwright scraper returns `ScrapeResponse`
   - Calls `PriceExtractionService` → `PriceExtractionOrchestrator` routes based on `extractionSource` (see waterfall below)
   - Validates the extracted `PriceInfo` before saving (non-zero, delta check, currency consistency)
   - Upserts `Product` and `TrackedItem` (by URL) in Postgres
   - Appends a new `PriceRecord` with the extracted price, timestamp, and `extractionSource`

**Price extraction waterfall** (each tier attempted in order; first success short-circuits):
```
Pre-step — DOM pruning in scraper            → strips nav/footer/ads/scripts from the live DOM
Tier 1   — JSON-LD / Schema.org in scraper   → returns structured PriceData directly; no LLM called
Tier 2   — CSS/meta selectors in scraper     → returns a ~100–500 char snippet; LLM called on snippet
Tier 3   — Regex line-filter in backend      → filterLines() reduces pruned innerText to ~2000 chars; LLM called
```
The scraper response carries an `extractionSource` enum (`STRUCTURED | SNIPPET | FULLTEXT`) so the backend knows which path to take without inspecting the content.

**Domain model:**
- `Product` — has many `TrackedItem`s (cascade ALL, orphanRemoval)
- `TrackedItem` — belongs to a `Product`, has a `url` + `shopName`, has many `PriceRecord`s
- `PriceRecord` — immutable price snapshot (BigDecimal, LocalDateTime set via `@PrePersist`, availability flag, `extractionSource`)
- `PriceInfo` — Java record DTO carrying `price`, `currency`, `available`, and `extractionSource`
- `ExtractionSource` — shared enum (`STRUCTURED | SNIPPET | FULLTEXT`) used across `ScrapeResponse`, `PriceInfo`, and `PriceRecord`

**Python scraper service (`scraper/`):**
- FastAPI app, single endpoint `POST /scrape { "url" }` → `ScrapeResponse { extractionSource, priceData?, snippet?, innerText? }`
- Runs DOM pruning, then tries Tier 1 (JSON-LD), Tier 2 (CSS selectors), falls back to Tier 3 (pruned innerText)
- Each tier wrapped in `try/except` — failure falls through to the next tier
- `ScraperClient.java` (`client/` package) wraps `RestClient` calls to it, URL configured via `scraper.base-url`

**AI integration:**
- `PriceExtractionService` is an interface; `PriceExtractionOrchestrator` is the `@Primary` implementation (routes the waterfall)
- `OllamaPriceExtractionService` is a plain `@Service` (not the interface impl) — called only for SNIPPET and FULLTEXT paths
- Uses Spring AI `ChatClient` with structured output to parse LLM responses directly into `PriceInfo`
- Ollama runs locally via Docker Compose (no external API keys required)

**Validation layer** (in `ProductTrackingService`, before saving `PriceRecord`):
- Price must be > 0
- If a prior price exists for the `TrackedItem`, new price must not differ by more than 500% (hallucination guard)
- Currency change is logged as a warning; does not block the save

## Key Conventions

- Constructor injection via Lombok `@RequiredArgsConstructor` (all injected fields are `final`)
- Lombok `@Data` / `@Builder` / `@NoArgsConstructor` / `@AllArgsConstructor` on domain entities
- Repositories extend `JpaRepository` with custom query methods (no `@Query` annotations — method name conventions)
- Monetary values use `BigDecimal` (precision 19, scale 4)
- The single existing test class is `@Disabled` — tests are not yet implemented

## Roadmap

**Phase 1 (done):** Synchronous HTTP pipeline — user submits URL → Spring Boot calls Python scraper → Ollama extracts price → stored in Postgres.

**Phase 1.5 (in progress):** Efficient price extraction waterfall — DOM pruning + JSON-LD → CSS selectors → regex-filtered LLM fallback. Eliminates LLM calls for most major e-commerce sites.

**Phase 1.6 (next after waterfall):** Selector caching — when LLM fires, it also returns the CSS selector it found the price in; stored on `TrackedItem`; subsequent checks use the selector directly and skip the waterfall entirely. Self-heals if selector stops returning data.

**Phase 2 (future):** Kafka async pipeline. Replace synchronous scraper call with:
- Spring Boot publishes `ScrapeRequestedEvent` to `price-tracker.scrape-requests` topic
- Python scraper consumes, scrapes, publishes `ScrapeCompletedEvent` to `price-tracker.scrape-results`
- Spring Boot consumes result, calls Ollama if needed, saves `PriceRecord`
- `POST /api/products/track` returns `202 Accepted` with a `requestId`
- `PriceCheckScheduler` (`@Scheduled`) publishes events for all active `TrackedItem`s hourly

**Phase 3 (future):** Price change detection → `PriceDroppedEvent` → notification (email/push).

## Infrastructure

- **Database:** PostgreSQL — credentials in `compose.yaml` (do not commit credentials to git)
- **LLM:** Ollama (local, via Docker)
- **Scraper:** Python FastAPI + Playwright at `localhost:8001` (built from `scraper/Dockerfile` by Docker Compose)
- **Kafka** — in `pom.xml`, wired up in Phase 2
- Spring Boot version: **4.0.3** | Spring AI version: **2.0.0-M2** | Java: **21**
