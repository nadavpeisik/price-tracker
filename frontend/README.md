# PriceHunt frontend

React SPA for the tracked-items dashboard (issue #144). Stack: **Vite +
React 19 + TypeScript + Tailwind CSS v4 + shadcn/ui (Radix) + TanStack Query
+ Motion**.

## Run

```bash
nvm use            # Node 22 (.nvmrc); engines are enforced as >=20.19 <23
npm ci
npm run dev        # http://localhost:5173 — mock data by default (see below)
```

Dev serves the dashboard from **typed mock data** (`.env.development` sets
`VITE_USE_MOCK=true`) because the backend dashboard endpoint doesn't exist
yet (#145/#146 own it). The mock client implements the same
`DashboardQuery → DashboardResponse` contract, so live wiring is a URL swap
in `src/lib/api-client.ts`. The Vite dev server proxies `/api` →
`http://localhost:8080` (Spring) for when it does.

**Mock data can never ship:** mock imports sit behind `import.meta.env.DEV`
(dead-code-eliminated from prod bundles); a production build with
`VITE_USE_MOCK=true` **fails** — the gate in `vite.config.ts` resolves the
flag via `loadEnv`, so it catches the value whether it comes from a shell
export or an `.env.production` file; and CI greps the compiled bundle for a
mock-only sentinel as a backstop. Production currently renders a placeholder
screen instead of the dashboard until the backend lands.

## Scripts

| Script | What |
|---|---|
| `npm run dev` | Dev server with HMR (mock data) |
| `npm test` / `npm run test:watch` | Vitest + React Testing Library |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc -b` |
| `npm run build` | Typecheck + production bundle |

## Layout

- `src/lib/` — types (view model + query contract), API client/adapter,
  formatting, URL-state store, shop/product colors, safe storage/URL guards
- `src/mocks/` — DEV-only mock data builder + mock dashboard client
- `src/hooks/` — theme, URL query state, shared 60s ticker, reduced motion,
  count-up
- `src/components/dashboard/` — the screen; `src/components/ui/` — vendored
  shadcn components
- `design/` — committed design reference: mockup source, token sheet,
  motion notes (the visual source of truth for reviewers)
- `public/fonts/` — self-hosted Nunito Sans (rounded display face) + license
