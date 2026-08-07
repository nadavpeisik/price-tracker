# Design tokens — tracked-items dashboard (#144)

Extracted from `mockup.html` (the agreed visual direction). The app maps these
onto Tailwind v4 `@theme` variables / shadcn semantic variables in
`src/index.css` — this sheet is the reference for reviewers.

## Color — light theme

| Token | Value | Role |
|---|---|---|
| `--bg` | `#F1EDE7` | page ground (warm neutral, not cream-cliché) |
| `--surface` | `#FFFFFF` | cards, list, inputs |
| `--surface-2` | `#FAF7F2` | list header, expanded panel ground, flat-delta pill |
| `--border` | `#E7E0D5` | hairlines |
| `--border-strong` | `#D8CFC0` | input/chip borders |
| `--ink` | `#201D19` | primary text |
| `--ink-muted` | `#6C655B` | secondary text |
| `--ink-faint` | `#98907F` | tertiary text, placeholders, column heads |
| `--accent` | `#5A57D6` | iris brand — primary actions only |
| `--accent-strong` | `#4A46C7` | hover / link text |
| `--accent-soft` | `#ECEBFB` | brand tints (tile wash, active "All shops" chip) |
| `--good` | `#0C9B50` | price DROP (semantic, never brand) |
| `--good-soft` | `#E1F4E9` | drop pill / in-stock badge ground |
| `--good-strong` | `#0A7E41` | drop pill / in-stock badge text |
| `--bad` | `#E23B44` | price RISE / out of stock |
| `--bad-soft` | `#FBE6E7` | rise pill / out badge ground |
| `--warn` | `#C67F09` | UNKNOWN availability (amber) |
| `--warn-soft` | `#F7EDD6` | unknown badge ground |

## Color — dark theme

| Token | Value |
|---|---|
| `--bg` | `#14120D` |
| `--surface` | `#201C15` |
| `--surface-2` | `#1A1710` |
| `--border` | `#2F2A20` |
| `--border-strong` | `#3B3526` |
| `--ink` | `#F3EFE6` |
| `--ink-muted` | `#A69E8F` |
| `--ink-faint` | `#766F5E` |
| `--accent` | `#908DFF` (strong `#ABA8FF`, soft `#242150`) |
| `--good` | `#40CE82` (soft `#10331F`, strong `#56DC93`) |
| `--bad` | `#FF6B6B` (soft `#391C1C`) |
| `--warn` | `#ECB44C` (soft `#37280E`) |

## Shop hues (mockup's 4 known shops)

Light: KSP `#0FA097`/soft `#DCF3F1` · Bug `#8155E6`/`#EDE6FB` · TMS `#2F6FE0`/`#E1EAFB` · Ivory `#D63C93`/`#FBE4F1`
Dark:  KSP `#2FC6BB`/`#103330` · Bug `#A98BFF`/`#241a45` · TMS `#5D97F2`/`#142744` · Ivory `#F063B0`/`#3d1730`

The REAL app does NOT hardcode these four: it hashes the normalized shop name
into a curated palette of ~8–10 hues in this spirit (each with light+dark
variants, WCAG AA checked). These pairs seed that palette.

## Type

| Role | Mockup | App |
|---|---|---|
| Display (brand, tile values, avatar initials) | `ui-rounded, "SF Pro Rounded"` — Apple-only | self-hosted rounded WOFF2 (see fonts/) |
| Body | system sans stack | same |
| Prices / numerics | `ui-monospace, SF Mono, Menlo` + `tabular-nums` | mono stack + `tabular-nums` |

Sizes: brand 20px/700; tile value 27px/700 (`-0.02em`); tile label 11px/600
uppercase `.07em`; product title 14.5px/650; subline 12px; best price 17px/600
mono; listing price 14.5px/600 mono; delta pill 12.5px/700; badge 11.5px/600;
column head 10.5px/700 uppercase `.06em`; chip 13px/600.

## Shape / elevation / spacing

- Radii: `--radius: 16px` (cards, list), `--radius-sm: 10px`; buttons 10px;
  inputs/sort 11px; chips + badges 999px (pill); avatar 11px (38×38);
  delta pill 8px.
- Shadows: rest `0 1px 2px rgba(32,29,25,.04), 0 6px 20px -8px rgba(32,29,25,.13)`;
  hover `0 2px 4px rgba(32,29,25,.05), 0 18px 36px -12px rgba(32,29,25,.2)`
  (dark equivalents use black at higher alpha).
- App shell: `max-width: 1080px`, padding `26px 22px 90px`.
- List grid (header + row share it): `minmax(190px,1fr) 132px 92px 118px 40px`,
  gap 12px; row padding `13px 18px`.
- Listing (expanded) grid: `minmax(150px,1fr) 120px 92px 110px 72px`,
  padding `10px 18px 10px 30px`, dashed top border.
- Tiles: 3-up grid, gap 13px; padding `16px 18px`; wash tiles use a
  `linear-gradient(150deg, <soft> , var(--surface) 70%)`.
- Mobile (`≤760px`): tiles stack 1-col; list header hidden; row regrids to
  `1fr auto 32px`; listing rows hide delta+badge.

## Background "glow"

Fixed, `z-index: -1`, opacity .55 — four radial blooms in shop/semantic tints:

```css
radial-gradient(38vw 38vw at 10%  3%, color-mix(in srgb, <bug>   26%, transparent), transparent 60%),
radial-gradient(34vw 34vw at 93%  2%, color-mix(in srgb, <ivory> 24%, transparent), transparent 60%),
radial-gradient(46vw 42vw at 80% 97%, color-mix(in srgb, <tms>   22%, transparent), transparent 62%),
radial-gradient(30vw 30vw at  3% 94%, color-mix(in srgb, <good>  16%, transparent), transparent 60%);
```

This glow is the FINAL background — the mockup's rockets/none switcher was a
preview tool only.

## Expanded-row accent

Each product carries an accent (`--pa`, first gradient stop of its avatar).
Open row: header ground `color-mix(in srgb, var(--pa) 12%, var(--surface))` +
inset 3px left bar in `--pa`; panel ground `color-mix(… 5% …)`.

## Sparkline

66×26 SVG, stroke-width 2, round caps/joins, y-padding 2px. App version is
responsive (`viewBox` + `preserveAspectRatio="none"` + non-scaling stroke) and
time-plotted — see #144 for the degenerate-case rules.
