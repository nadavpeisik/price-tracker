# PriceHunt frontend

React SPA for the tracked-items dashboard (issue #144). Stack: **Vite +
React 19 + TypeScript + Tailwind CSS v4 + shadcn/ui (Radix) + TanStack Query
+ Motion**.

## Run

```bash
nvm use            # Node 22 (.nvmrc); engines are enforced as >=20.19 <23
npm ci
npm run dev        # http://localhost:5173 — LIVE data via the /api proxy (see below)
```

Dev serves the dashboard from the **real backend**: the Vite dev server
proxies `/api` → `http://localhost:8080` (Spring), so start the backend first
— ideally with the dev seeder so there is something to look at:

```bash
cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
```

(22 back-dated demo products across two dashboard pages, incl. the awkward
states — mixed currencies, out-of-stock, never-checked, gone-cold; see the
root README.)

**Offline UI work — mock mode.** `src/mocks/` is a typed mock client that
implements the same `DashboardQuery → DashboardResponse` and listings
contracts as the backend. Turn it on for one run with
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
| `npm run dev` | Dev server with HMR (live data via the `/api` proxy; `VITE_USE_MOCK=true` for mock) |
| `npm test` / `npm run test:watch` | Vitest + React Testing Library |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc -b` |
| `npm run build` | Typecheck + production bundle |

## Layout

- `src/lib/` — types (view model + query contract), API client/adapter,
  formatting, URL-state store, shop/product colors, safe storage/URL guards
- `src/mocks/` — DEV-only mock data builder + mock dashboard/listings client (opt-in)
- `src/hooks/` — theme, URL query state, shared 60s ticker, reduced motion,
  count-up
- `src/components/dashboard/` — the screen; `src/components/ui/` — vendored
  shadcn components
- `design/` — committed design reference: mockup source, token sheet,
  motion notes (the visual source of truth for reviewers)
- `public/fonts/` — self-hosted Nunito Sans (rounded display face) + license
