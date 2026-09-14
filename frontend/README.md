# PriceHunt frontend

React SPA for the tracked-items dashboard (issue #144). Stack: **Vite +
React 19 + TypeScript + Tailwind CSS v4 + shadcn/ui (Radix) + TanStack Query
+ Motion**.

## Run

```bash
nvm use            # Node 22 (.nvmrc); engines are enforced as >=20.19 <23
npm ci
npm run dev        # http://localhost:5173 — LIVE data via the /bff proxy (see below)
```

Dev serves the dashboard from the **real backend through the BFF** (#247,
#248): you sign in with Auth0, the BFF holds the session in a cookie and
proxies `/bff/api/**` to Spring with the bearer token. The Vite dev server
proxies `/bff` → `http://localhost:8082`, so start both servers first —
the backend ideally with the dev seeder so there is something to look at:

```bash
# 1. backend (auto-starts Postgres + scraper via Docker Compose)
cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
# 2. BFF (needs AUTH0_CLIENT_ID / AUTH0_CLIENT_SECRET exported; .env supplies the rest)
set -a; . ../.env; set +a; cd ../bff && ./mvnw spring-boot:run
```

(22 back-dated demo products across two dashboard pages, incl. the awkward
states — mixed currencies, out-of-stock, never-checked, gone-cold; see the
root README.)

**First sign-in on a fresh database is a "not set up yet" screen.** Until
#249 there is no invitation flow: the database holds only the placeholder
account, and the backend admits an identity only if an `app_user` row names
it. Run the one-off relink `UPDATE` from the root `CLAUDE.md` ("Dev bootstrap
until #249") once, then reload. The BFF's session store also needs its
Postgres role once per volume (`create-bff-role.sh`, see the BFF section of
`CLAUDE.md`).

The session cookie is `__Host-` prefixed, which Chrome and Firefox accept on
`http://localhost` and Safari does not — use one of the former for dev.

**Offline UI work — mock mode.** `src/mocks/` is a typed mock client that
implements the same `DashboardQuery → DashboardResponse` and listings
contracts as the backend, and `auth-client.ts` signs you in as a fixture
user (no BFF needed). Turn it on for one run with
`VITE_USE_MOCK=true npm run dev` (an inline env var beats every `.env` file),
or persistently in a gitignored `frontend/.env.development.local` — **not**
`.env.local`, which the committed mode-specific `.env.development` outranks
(Vite loads `.env.[mode].local` > `.env.[mode]` > `.env.local` > `.env`). It is
DEV-only.

**Mock data can never ship:** mock imports sit behind `import.meta.env.DEV`
(dead-code-eliminated from prod bundles); a production build with
`VITE_USE_MOCK=true` **fails** — the gate in `vite.config.ts` resolves the
flag via `loadEnv`, so it catches the value whether it comes from a shell
export or an `.env.production` file; and CI greps the compiled bundle for a
mock-only sentinel as a backstop.

## Scripts

| Script | What |
|---|---|
| `npm run dev` | Dev server with HMR (live data via the `/bff` proxy; `VITE_USE_MOCK=true` for mock) |
| `npm test` / `npm run test:watch` | Vitest + React Testing Library |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc -b` |
| `npm run build` | Typecheck + production bundle |

## Layout

- `src/lib/` — types (view model + query contract), API client/adapter,
  BFF session client (`auth-client.ts`), the QueryClient with its 401 rule
  (`query-client.ts`), CSRF cookie reader, formatting, URL-state store,
  shop/product colors, safe storage/URL guards
- `src/mocks/` — DEV-only mock data builder + mock dashboard/listings client (opt-in)
- `src/hooks/` — theme, URL query state, sign-out, shared 60s ticker, reduced
  motion, count-up
- `src/components/auth/` — `AuthGate` (sign-in / forbidden / shell decision),
  `AppShell` (container + header + account cluster), the two pre-auth screens
- `src/components/dashboard/` — the screen; `src/components/ui/` — vendored
  shadcn components
- `design/` — committed design reference: mockup source, token sheet,
  motion notes (the visual source of truth for reviewers)
- `public/fonts/` — self-hosted Nunito Sans (rounded display face) + license
