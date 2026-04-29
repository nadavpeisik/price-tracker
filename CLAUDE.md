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
   - Calls `ScraperClient` → `POST http://localhost:8001/scrape` → Python Playwright scraper returns `innerText`
   - Calls `PriceExtractionService` → `OllamaPriceExtractionService` sends innerText to local Ollama LLM and maps structured JSON response to `PriceInfo` record
   - Upserts `Product` and `TrackedItem` (by URL) in Postgres
   - Appends a new `PriceRecord` with the extracted price and timestamp

**Domain model:**
- `Product` — has many `TrackedItem`s (cascade ALL, orphanRemoval)
- `TrackedItem` — belongs to a `Product`, has a `url` + `shopName`, has many `PriceRecord`s
- `PriceRecord` — immutable price snapshot (BigDecimal, LocalDateTime set via `@PrePersist`, availability flag)
- `PriceInfo` — Java record DTO returned by the AI extraction layer

**Python scraper service (`scraper/`):**
- FastAPI app, single endpoint `POST /scrape { "url" }` → `{ "innerText" }`
- Uses Playwright (headless Chromium) to load the page and return `body.innerText` — raw text only, keeping Ollama context window lean
- `ScraperClient.java` (`client/` package) wraps `RestClient` calls to it, URL configured via `scraper.base-url`

**AI integration:**
- `PriceExtractionService` is an interface; `OllamaPriceExtractionService` is the only implementation
- Uses Spring AI `ChatClient` with structured output to parse LLM responses directly into `PriceInfo`
- Ollama runs locally via Docker Compose (no external API keys required)

## Key Conventions

- Constructor injection via Lombok `@RequiredArgsConstructor` (all injected fields are `final`)
- Lombok `@Data` / `@Builder` / `@NoArgsConstructor` / `@AllArgsConstructor` on domain entities
- Repositories extend `JpaRepository` with custom query methods (no `@Query` annotations — method name conventions)
- Monetary values use `BigDecimal` (precision 19, scale 4)
- The single existing test class is `@Disabled` — tests are not yet implemented

## Roadmap

**Phase 1 (done):** Synchronous HTTP pipeline — user submits URL → Spring Boot calls Python scraper → Ollama extracts price → stored in Postgres.

**Phase 2 (next):** Kafka async pipeline. Replace synchronous scraper call with:
- Spring Boot publishes `ScrapeRequestedEvent` to `price-tracker.scrape-requests` topic
- Python scraper consumes, scrapes, publishes `ScrapeCompletedEvent` to `price-tracker.scrape-results`
- Spring Boot consumes result, calls Ollama, saves `PriceRecord`
- `POST /api/products/track` returns `202 Accepted` with a `requestId`
- `PriceCheckScheduler` (`@Scheduled`) publishes events for all active `TrackedItem`s hourly

**Phase 3 (future):** Price change detection → `PriceDroppedEvent` → notification (email/push).

## Infrastructure

- **Database:** PostgreSQL — credentials in `compose.yaml` (`myuser`/`secret`, db `mydatabase`)
- **LLM:** Ollama (local, via Docker)
- **Scraper:** Python FastAPI + Playwright at `localhost:8001` (built from `scraper/Dockerfile` by Docker Compose)
- **Kafka** — in `pom.xml`, wired up in Phase 2
- Spring Boot version: **4.0.3** | Spring AI version: **2.0.0-M2** | Java: **21**
