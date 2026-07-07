# Motion / interaction states — tracked-items dashboard (#144)

What the mockup demonstrates, as implementation-independent intent. The app
implements these with Motion (Framer Motion); the mockup used raw CSS/WAAPI.
**All of it is disabled under `prefers-reduced-motion`** (including runtime
toggles of the preference, not just its initial value).

## 1. Row expand / collapse

- Trigger: the row header (a scoped semantic `<button>` in the app — the
  mockup wrapped the whole row, which #144 explicitly corrects for a11y).
- Chevron rotates 0° → 90° over ~220ms.
- Panel reveals top-down ~280ms ease (mockup: `grid-template-rows: 0fr → 1fr`;
  app: Motion height/layout animation).
- Open state: header ground tints in the product accent (12% mix) with a 3px
  inset left bar; panel ground gets a 5% tint. Collapse reverses everything.

## 2. Everyday load reveal + count-up

- Tiles, toolbar, list stagger in top-down: each starts `opacity 0,
  translateY(12px)` → settles over ~500ms (`cubic-bezier(.2,.7,.3,1)`),
  ~70ms between siblings (mockup rows used ~40ms).
- Tile numbers count up 0 → value over ~800ms with cubic ease-out
  (`1 - (1-p)^3`), rounded to integers while animating.
- App-only cost cap (#144): stagger + count-up apply to the first ~15 rows by
  index; later rows render immediately in final state.

## 3. Price-drop celebration (small, per-row)

- Row pulse: inset 3px green bar + `--good-soft` wash swelling in and fading
  out over ~1.4s (`pulseRow` keyframes).
- Coin burst: ~16 currency-symbol particles (₪ $ € £ ¥ ₿ ₩, random palette
  colors, 16–26px) explode from the price element — random angle, distance
  ~52–170px with slight upward bias, rotate up to ±360°, scale .4 → 1 → .9,
  fade at the end, ~1.1–1.6s per particle, then fall ~60px.
- Price ticks DOWN to the new value over ~700ms (same cubic ease-out).
- App-only trigger rules (#144): only for a LIVE drop observed between
  successful refetches of the same query (keyed by product id), never on
  initial load, never for remounted rows after sort/filter/page.

## 4. First-run rocket intro

Full-screen overlay in `--bg`, "launching PriceHunt…" hint, click to skip.

- Rocket 🚀 (62px) launches from below the fold to ~52% viewport height over
  ~900ms (`cubic-bezier(.3,.1,.3,1)`), tilting −6° → +4° → 0°.
- Exhaust trail: 1 small particle burst every ~55ms at the rocket's tail.
- At apex (~900ms): rocket hides; a radial flash (white → amber) scales 0 → 9×
  over ~520ms while ~30 large (22px+) currency symbols burst 240px outward.
- ~620ms later the overlay fades (~500ms) and the app content starts its
  staggered reveal.
- App-only gating (#144): plays ONCE per browser (persisted first-run flag via
  the safe-storage helper), and only when the dashboard query resolves
  success + zero items. The zero-items empty state afterwards shows a static/
  gentle rocket illustration — it never re-runs the launch on every visit.

## Mockup-only elements (do NOT ship)

- Bottom "Preview" pill: replay intro / simulate drop / background switcher.
- Canvas rocket-chase background ("Rockets" mode). The ambient glow is final.
- The `body.bg-*` class switching that supported it.
