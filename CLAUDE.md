# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo Structure

This is a monorepo with its git root at `price-tracker/`. This file (`CLAUDE.md`)
lives at the git root and is also symlinked as `AGENTS.md` so Codex CLI picks up the
same instructions (issue #81) — both names resolve to identical content. Windows
clones need `git config core.symlinks true` (set *before* checkout), or `AGENTS.md`
checks out as a one-line text file containing the literal string `CLAUDE.md` instead
of a working symlink; verify with `cat AGENTS.md` after checkout if in doubt.

```text
price-tracker/
├── compose.yaml      ← orchestrates postgres, scraper, and grafana (Ollama runs natively, not via Compose)
├── backend/          ← Spring Boot backend
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

`compose.yaml` lives at the repo root (`../compose.yaml`). Spring Boot finds it via `spring.docker.compose.file=../compose.yaml` in `application.properties`. It spins up PostgreSQL (5432), the Python scraper (8001), and Grafana (3000) automatically — no manual `docker-compose up` needed. Ollama (11434) runs **natively** (not via Docker Compose) — start it separately (`ollama serve`) before relying on LLM-based price extraction.

## Code style & linting

CI (`.github/workflows/ci.yml`) fails on formatting violations in **both** languages, so format before pushing:

- **Java (backend):** `./mvnw spotless:apply` from `backend/`, then re-stage. CI runs `spotless:check` via `./mvnw verify`.
- **Python (scraper):** ruff. CI runs `ruff check .` + `ruff format --check .`. A **pre-commit hook** (`.pre-commit-config.yaml`, ruff pinned to match CI) auto-fixes on commit — run `pre-commit install` once per clone (`pip install -e '.[dev]'` provides `pre-commit`). When the hook auto-fixes files the commit **aborts**: re-stage (`git add`) the fixed files and commit again. To fix manually instead: `cd scraper && ruff check --fix . && ruff format .`.

## Local pre-commit review (Gemini + Codex)

**After writing code and before committing**, run TWO local reviews — Gemini (via Antigravity's `agy`) and Codex — so fixes from either land in the *same* commit (clean history) rather than as follow-up "address review" commits. Per issue #81: every time we write new code, we get advised by two other LLMs.

```bash
# Run from the repo root (price-tracker/), not backend/
scripts/agy-review.sh              # Gemini: everything not yet on origin/main
scripts/codex-review.sh            # Codex:  everything not yet on origin/main
scripts/agy-review.sh --staged     # Gemini: only what's staged
scripts/codex-review.sh --staged   # Codex:  only what's staged
```

Workflow: **write code → run BOTH `scripts/agy-review.sh` AND `scripts/codex-review.sh` → surface BOTH raw reviews (paste verbatim, never paraphrase-in-place) → fix accepted findings from either → then commit.**

Both scripts get their diff from the same shared helper, `scripts/get-review-diff.sh` — **Gemini and Codex review the exact same diff text**, so a disagreement between them reflects different judgment, not different scope.

- **`agy-review.sh`** wraps the Antigravity CLI (`agy -p`), default model **Gemini 3.5 Flash (High)** (`AGY_REVIEW_MODEL`, switch to `Gemini 3.1 Pro (High)` off the free tier).
- **`codex-review.sh`** wraps plain `codex exec --sandbox read-only -`, using Codex CLI's configured default model unless overridden (`CODEX_REVIEW_MODEL`), reasoning effort **high** by default (`CODEX_REVIEW_REASONING_EFFORT`).

Both produce findings grouped **HIGH / MEDIUM / LOW**, ending with `VERDICT:`. Both preserve Gemini-bot review quality locally after the GitHub `gemini-code-assist` bot sunsets (2026-07-17), and add a second, independent model's perspective per issue #81's "second opinion" role. Run either script with `--help` for the full environment-variable reference. On a quota/rate-limit, either script prints `REVIEW FAILED` to stderr (exit 2) — never mistake this for a clean review.

Guardrails (per issue #81's coordination model):
- **Read-only:** `agy-review.sh` runs `agy --sandbox`; `codex-review.sh` runs `codex exec --sandbox read-only`. Neither can edit/commit/push — only review. **One owner per branch:** Claude implements, Gemini AND Codex critique.
- **Surface BOTH raw reviews** to the user every time; **the human breaks ties** when Gemini and Codex disagree with Claude or with each other.
- **Bounded rounds:** stop re-reviewing once a round yields no accepted findings from either tool (don't ping-pong).
- **CI + SonarCloud remain the objective backstop** — both reviews are advisory, not a gate.

## Local plan review (Gemini + Codex)

One step earlier than the diff review: **before implementing a new code-implementation plan, get both reviewers' takes on the *plan* itself.** Catching a flawed approach at the plan stage is far cheaper than catching it in code review.

**When:** in plan mode, for plans that involve writing code — after the plan file is written, **before** calling `ExitPlanMode`. Skip it for trivial/non-code plans (research-only, config tweaks, doc edits).

```bash
# Run from the repo root (price-tracker/), not backend/
scripts/agy-plan-review.sh ~/.claude/plans/<plan-file>.md   # Gemini: review a specific plan
scripts/codex-plan-review.sh ~/.claude/plans/<plan-file>.md # Codex:  review a specific plan
scripts/agy-plan-review.sh                                  # Gemini: newest plan in ~/.claude/plans
scripts/codex-plan-review.sh                                # Codex:  newest plan in ~/.claude/plans
```

Workflow: **write plan → run BOTH `scripts/agy-plan-review.sh` AND `scripts/codex-plan-review.sh` → surface BOTH raw reviews verbatim → human decides (implement as-is / revise the plan / dismiss) → then `ExitPlanMode`.**

- **`agy-plan-review.sh`** shares `AGY_REVIEW_MODEL` / `AGY_REVIEW_TIMEOUT` / `AGY_REVIEW_SANDBOX` with `agy-review.sh`; plan dir via `AGY_PLAN_DIR`.
- **`codex-plan-review.sh`** shares `CODEX_REVIEW_MODEL` / `CODEX_REVIEW_REASONING_EFFORT` / `CODEX_REVIEW_TIMEOUT` with `codex-review.sh`; plan dir via `CODEX_PLAN_DIR` (falls back to `AGY_PLAN_DIR`). Always runs `codex exec --sandbox read-only` — unlike agy's all-or-nothing sandbox, this blocks all writes while letting Codex read the codebase (including `AGENTS.md`, auto-loaded as context) for a grounded critique.

Both produce findings grouped **HIGH / MEDIUM / LOW**, ending with `VERDICT:`.

Guardrails (same model as the diff review and issue #81):
- **Read-only:** `agy-plan-review.sh` runs `agy --sandbox` (set `AGY_REVIEW_SANDBOX=0` for codebase access); `codex-plan-review.sh` always runs `codex exec --sandbox read-only`, which permits codebase reads while blocking all writes — no toggle needed.
- **Surface BOTH raw reviews** to the user every time; **the human breaks ties** when Gemini and Codex disagree with Claude or with each other.
- **Bounded rounds:** stop re-reviewing once a round yields no accepted findings from either tool (don't ping-pong).
- **Advisory, not a gate** — the human approves the plan via `ExitPlanMode`, not Gemini or Codex.

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
- Ollama runs locally, natively (no external API keys required)

**Prompt changes — hard rule:** whenever you edit the extraction prompt in
`OllamaPriceExtractionService.java` (system or user template), run the prompt-regression
sanity check **before committing** and confirm it passes:

```bash
scripts/run-ollama-prompt-regression.sh            # default: qwen3:1.7b (the SNIPPET model)
scripts/run-ollama-prompt-regression.sh qwen3.5:9b # optional: spot-check the FULLTEXT model
```

This drives the real service over labeled snippets (`backend/src/test/resources/price-extraction/availability-cases.json`)
and asserts the extracted `available`/price/currency — a small prompt tweak can silently
flip availability (issue #102). Needs `ollama serve` running. It is **manual, not CI**
(`OllamaPromptRegressionIT` is `*IT` + env-gated by `RUN_OLLAMA_PROMPT_REGRESSION=true`),
so nothing runs it for you. When you add or change a prompt rule, add a covering case to
the fixtures JSON in the same change.

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
- **LLM:** Ollama (local, runs **natively** — not via Docker Compose; `compose.yaml` orchestrates only `postgres`, `scraper`, and `grafana`)
- **Scraper:** Python FastAPI + Playwright at `localhost:8001` (built from `scraper/Dockerfile` by Docker Compose)
- **Kafka** — in `pom.xml`, wired up in Phase 2
- **Dashboards:** Grafana 11.4.0 at `localhost:3000` (admin/admin local-only — gate before any cloud deploy). Provisioned datasource + dashboards under `infra/grafana/`. All time-scoped Postgres panels MUST use the Grafana `$__timeFilter(column)` macro — hardcoded `WHERE x > NOW() - INTERVAL ...` makes the dashboard's time picker inert. New dashboards: drop a JSON into `infra/grafana/dashboards/`; the file provider picks it up every 30s.
- Spring Boot version: **4.0.3** | Spring AI version: **2.0.0-M2** | Java: **21**
