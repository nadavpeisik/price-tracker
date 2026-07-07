# Self-hosted fonts

| File | Family | Axes | Source | License |
|---|---|---|---|---|
| `nunito-sans-latin-wght-normal.woff2` | Nunito Sans (variable) | `wght` 200–1000, latin subset | npm `@fontsource-variable/nunito-sans@5.2.7` (`files/nunito-sans-latin-wght-normal.woff2`, unmodified) | SIL OFL 1.1 — see `LICENSE-nunito-sans.txt` |

Nunito Sans is the rounded *display* face (brand, tile values, avatar
initials) required by #144 — `ui-rounded`/SF Pro Rounded is Apple-only, so
without a bundled webfont the design silently loses its personality on
Windows/Linux. Body text uses the system sans stack; Hebrew content renders
through the body stack (this latin subset never receives Hebrew text).

Files live in `public/` (not `src/`) so they are copied verbatim to the build
root and remain preloadable at the stable absolute path
`/fonts/nunito-sans-latin-wght-normal.woff2` (see the `<link rel="preload">`
in `index.html`). The `@font-face` declaration lives in `src/index.css`.

To upgrade: bump the fontsource package version, re-copy the same file +
LICENSE, and update this table.
