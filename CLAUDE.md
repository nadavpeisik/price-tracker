# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo Structure

This is a monorepo. The git root is `price-tracker/`, one level above this directory.

```
price-tracker/
├── compose.yaml      ← orchestrates all services (postgres, ollama, scraper, grafana)
├── backend/          ← Spring Boot (this directory)
├── scraper/          ← Python FastAPI + Playwright scraper
└── infra/grafana/    ← provisioned Grafana datasource + dashboards
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

`compose.yaml` lives at the repo root (`../compose.yaml`). Spring Boot finds it via `spring.docker.compose.file=../compose.yaml` in `application.properties`. It spins up PostgreSQL (5432), Ollama (11434), the Python scraper (8001), and Grafana (3000) automatically — no manual `docker-compose up` needed.

## Code style & linting

CI (`.github/workflows/ci.yml`) fails on formatting violations in **both** languages, so format before pushing:

- **Java (backend):** `./mvnw spotless:apply` from `backend/`, then re-stage. CI runs `spotless:check` via `./mvnw verify`.
- **Python (scraper):** ruff. CI runs `ruff check .` + `ruff format --check .`. A **pre-commit hook** (`.pre-commit-config.yaml`, ruff pinned to match CI) auto-fixes on commit — run `pre-commit install` once per clone (`pip install -e '.[dev]'` provides `pre-commit`). When the hook auto-fixes files the commit **aborts**: re-stage (`git add`) the fixed files and commit again. To fix manually instead: `cd scraper && ruff check --fix . && ruff format .`.

## Local pre-commit review (Antigravity / Gemini)

**After writing code and before committing**, run a local Gemini review so fixes land in the *same* commit (clean history) rather than as follow-up "address review" commits:

```bash
scripts/agy-review.sh            # everything not yet on origin/main (committed + uncommitted + new files)
scripts/agy-review.sh --staged   # only what's staged
```

Workflow: **write code → `scripts/agy-review.sh` → surface the raw review → fix accepted findings → then commit.** This preserves the Gemini-bot review quality locally after the GitHub `gemini-code-assist` bot sunsets (2026-07-17). The script wraps the **Antigravity CLI** (`agy -p`), default model **Gemini 3.5 Flash (High)** (set `AGY_REVIEW_MODEL`, e.g. `Gemini 3.1 Pro (High)` off the free tier), and prints severity-tagged findings. On the free tier a quota/rate-limit shows as `REVIEW FAILED`, never as a clean review.

Guardrails (mirror the agent-coordination model in issue #81):
- **Read-only:** runs `agy --sandbox` — it can't edit/commit/push, only review. One owner per branch (Claude implements, Gemini critiques).
- **Surface the raw review** to the user every time; **the human breaks ties** when Gemini and Claude disagree.
- **Bounded rounds:** stop re-reviewing once a round yields no accepted findings (don't ping-pong).
- **CI + SonarCloud remain the objective backstop** — this review is advisory, not a gate.

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
Pre-step            — DOM pruning in scraper   → strips nav/footer/ads/scripts from the live DOM
Tier 1 (STRUCTURED) — JSON-LD / Schema.org     → returns structured PriceData directly; no LLM called
Tier 2 (SNIPPET)    — CSS/meta selectors       → returns a ~100–500 char snippet; LLM called on snippet
Tier 3 (FULLTEXT)   — Regex line-filter        → filterLines() reduces pruned innerText to ~2000 chars; LLM called
```
The scraper response carries an `extractionSource` enum (`STRUCTURED | SNIPPET | FULLTEXT`) so the backend knows which path to take without inspecting the content.

**Domain model:**
- `Product` — has many `TrackedItem`s (cascade ALL, orphanRemoval)
- `TrackedItem` — belongs to a `Product`, has a `url` + `shopName`, has many `PriceRecord`s
- `PriceRecord` — immutable price snapshot (BigDecimal, LocalDateTime set via `@PrePersist`, availability flag, `extractionSource`)
- `PriceInfo` — Java record DTO carrying `price`, `currency`, `available`, and `extractionSource`
- `ExtractionSource` — shared enum (`STRUCTURED | SNIPPET | FULLTEXT`) used across `ScrapeResponse`, `PriceInfo`, and `PriceRecord`
- `ScheduledJobRun` / `ScheduledJobRunItem` — audit trail for each `@Scheduled` execution; one parent row per run, one child row per item processed (URL for price refresh, currency for FX). Status uses the shared `JobStatus` enum (`RUNNING | SUCCESS | PARTIAL | FAILED`).

**Python scraper service (`scraper/`):**
- FastAPI app, single endpoint `POST /scrape { "url" }` → `ScrapeResponse { extractionSource, priceData?, snippet?, innerText? }`
- Runs DOM pruning, then tries Tier 1 (JSON-LD), Tier 2 (CSS selectors), falls back to Tier 3 (pruned innerText)
- Each tier wrapped in `try/except` — failure falls through to the next tier
- `ScraperClient.java` (`client/` package) wraps `RestClient` calls to it, URL configured via `scraper.base-url`

**AI integration:**
- `PriceExtractionService` is an interface; `PriceExtractionOrchestrator` is the sole implementation (routes the waterfall)
- `OllamaPriceExtractionService` is a plain `@Service` (not the interface impl) — called only for SNIPPET and FULLTEXT paths
- Uses Spring AI `ChatClient` with structured output to parse LLM responses directly into `PriceInfo`
- Ollama runs locally via Docker Compose (no external API keys required)

**Validation layer** (in `ProductTrackingService`, before saving `PriceRecord`):
- Price must be > 0
- If a prior price exists for the `TrackedItem` **and the currency matches**, new price must not differ by more than 200% (i.e. no more than 3x the previous price) — configurable via `price.validation.max-delta-percent` in `application.properties`
- Delta check is skipped entirely if the currency changed (cross-currency comparison is meaningless)
- Currency change is logged as a warning; does not block the save

## Scheduled job observability

Every `@Scheduled` method records its outcome through `JobRunRecorder` (in `observability/`):

- `start(jobName)` → returns `runId`; captures MDC `correlationId`.
- `recordItem(runId, label, status, durationMs, errorMessage)` per processed item.
- `complete(runId, status, processed, succeeded, failed, errorSummary)` in a finally block.

All three methods are `@Transactional(propagation = REQUIRES_NEW)` so audit rows commit independently of any scheduler work that fails or rolls back.

**Hard rule:** scheduler methods themselves must stay non-`@Transactional`. They make outbound HTTP calls (scraper, ECB) and holding a DB connection across that I/O starves the pool. Wiring the recorder in does not change that — only the recorder methods carry `@Transactional`.

New scheduler? Wire the recorder the same way `PriceCheckScheduler.refreshAll()` does: start at the top, per-item record inside the loop, complete in a finally, outer try/catch routes catastrophic failure into a final `complete(..., JobStatus.FAILED, ...)`.

## Key Conventions

- Constructor injection via Lombok `@RequiredArgsConstructor` (all injected fields are `final`)
- Lombok `@Data` / `@Builder` / `@NoArgsConstructor` / `@AllArgsConstructor` on domain entities
- Repositories extend `JpaRepository` with custom query methods (no `@Query` annotations — method name conventions)
- Monetary values use `BigDecimal` (precision 19, scale 4)
- The single existing test class is `@Disabled` — tests are not yet implemented

## Database migrations

Schema is managed by **Flyway**. SQL files live in `backend/src/main/resources/db/migration/`, named `V{n}__{snake_case_description}.sql`. Each migration runs once per database, in version order, on app startup. The `flyway_schema_history` table in Postgres records what's been applied.

**Hard rules:**
- Migrations are immutable. Once a `V{n}` is merged, never edit it — Flyway stores a checksum and refuses to start on mismatch. Bugs get fixed in `V{n+1}`.
- `spring.jpa.hibernate.ddl-auto=validate` means Hibernate no longer mutates the schema. At startup it just checks that entity classes match what's live; any drift fails the boot.
- When changing an entity (adding a column, renaming, etc.), write the matching migration in the same PR.

**Existing-DB baselining:** `spring.flyway.baseline-on-migrate=true` + `baseline-version=1`. On a DB that has tables but no `flyway_schema_history`, Flyway inserts a row marking V1 as already applied — without running it — and continues from V2. Fresh DBs (CI, new contributors) run V1 normally. This is how local dev DBs survived the Flyway adoption without being recreated.

**Tests:** `@DataJpaTest` uses in-memory H2. `application-test.properties` disables Flyway and switches Hibernate back to `create-drop` for tests — our migrations are Postgres-flavored and H2 fakes the syntax inconsistently. Temporary trade-off; the V2 PR (FX `ExchangeRate` IDENTITY → SEQUENCE) will require Testcontainers Postgres for repository tests since H2 cannot honor Postgres-specific sequence DDL.

**Spring Boot 4 dependency gotcha:** SB4 split per-feature autoconfigs into separate modules. Declaring only `org.flywaydb:flyway-core` puts the library on the classpath but no `FlywayAutoConfiguration` registers — Flyway sits dormant with no logs. The artifact that wires autoconfig is `org.springframework.boot:spring-boot-flyway` (transitively pulls `flyway-core`). Same pattern presumably applies to Liquibase, Redis, Kafka, etc. — when adding a third-party library that "just worked" by classpath in SB3, check the SB4 BOM for a matching `spring-boot-{feature}` module.

## Roadmap

**Phase 1 (done):** Synchronous HTTP pipeline — user submits URL → Spring Boot calls Python scraper → Ollama extracts price → stored in Postgres.

**Phase 1.5 (in progress):** Efficient price extraction waterfall — DOM pruning + JSON-LD → CSS selectors → regex-filtered LLM fallback. Eliminates LLM calls for most major e-commerce sites.

**Phase 1.6 (next after waterfall):** Selector caching — when LLM fires, it also returns the CSS selector it found the price in; stored on `TrackedItem`; subsequent checks use the selector directly and skip the waterfall entirely. Self-heals if selector stops returning data.

**Phase 1.7 (before cloud deploy):** SSRF hardening — add URL validation in the backend (`UrlValidator` component) that rejects private IP ranges (RFC-1918: 10.x, 172.16–31.x, 192.168.x) and cloud metadata endpoints (169.254.169.254, 100.100.100.200) before the scrape request is dispatched. The scheme check (`http`/`https` only) is already in the scraper; the IP blocklist belongs in the backend at the user-input boundary.

**Phase 2 (future):** Kafka async pipeline. Replace synchronous scraper call with:
- Spring Boot publishes `ScrapeRequestedEvent` to `price-tracker.scrape-requests` topic
- Python scraper consumes, scrapes, publishes `ScrapeCompletedEvent` to `price-tracker.scrape-results`
- Spring Boot consumes result, calls Ollama if needed, saves `PriceRecord`
- `POST /api/products/track` returns `202 Accepted` with a `requestId`
- `PriceCheckScheduler` (`@Scheduled`) publishes events for all active `TrackedItem`s hourly
- Migrate primary keys project-wide from BIGSERIAL/SEQUENCE to UUIDv7 — Kafka producers can mint IDs before the row hits the DB (no `getGeneratedKeys()` round-trip per insert). Until Phase 2 lands, `ExchangeRate` uses SEQUENCE; other entities use IDENTITY.

**Phase 3 (future):** Price change detection → `PriceDroppedEvent` → notification (email/push).

## Infrastructure

- **Database:** PostgreSQL — credentials in `compose.yaml` (do not commit credentials to git)
- **LLM:** Ollama (local, via Docker)
- **Scraper:** Python FastAPI + Playwright at `localhost:8001` (built from `scraper/Dockerfile` by Docker Compose)
- **Kafka** — in `pom.xml`, wired up in Phase 2
- **Dashboards:** Grafana 11.4.0 at `localhost:3000` (admin/admin local-only — gate before any cloud deploy). Provisioned datasource + dashboards under `infra/grafana/`. All time-scoped Postgres panels MUST use the Grafana `$__timeFilter(column)` macro — hardcoded `WHERE x > NOW() - INTERVAL ...` makes the dashboard's time picker inert. New dashboards: drop a JSON into `infra/grafana/dashboards/`; the file provider picks it up every 30s.
- Spring Boot version: **4.0.3** | Spring AI version: **2.0.0-M2** | Java: **21**
