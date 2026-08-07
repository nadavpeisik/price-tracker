/**
 * Deterministic shop colors (#144).
 *
 * The real system has arbitrary shops, so nothing is hardcoded per shop:
 * the shop name is NORMALIZED (trim + NFC + locale-independent lowercase —
 * "KSP" / "ksp " / "Ksp" collapse to one key) and hashed into a curated
 * palette of 8 hues. Every entry carries light+dark variants tuned for WCAG
 * AA (chip text ≥ 4.5:1 on its soft background, dot ≥ 3:1 on the theme
 * surface) — asserted by shop-colors.test.ts, so palette edits that break
 * contrast fail CI.
 *
 * Rendering uses STATIC class hooks + inline CSS-variable styles (the
 * `.shop-color` rule in index.css flips light/dark) — never dynamically
 * built Tailwind class names, which the static scanner can't see and would
 * purge from the prod bundle.
 */
import { hashString } from '@/lib/hash'

export interface ShopColorVariant {
  /** Chip/badge text — AA vs `bg`. */
  text: string
  /** Soft chip background. */
  bg: string
  /** Accent dot — ≥3:1 vs the theme surface. */
  dot: string
}

export interface ShopColor {
  light: ShopColorVariant
  dark: ShopColorVariant
}

/** Curated hues seeded from the mockup's four shop colors (see design/tokens.md). */
export const SHOP_PALETTE: readonly ShopColor[] = [
  {
    // teal
    light: { text: '#084C47', bg: '#DCF3F1', dot: '#0B837B' },
    dark: { text: '#8CE8DE', bg: '#103330', dot: '#2FC6BB' },
  },
  {
    // violet
    light: { text: '#38287C', bg: '#EDE6FB', dot: '#7A4FDC' },
    dark: { text: '#CBC0FF', bg: '#241A45', dot: '#A98BFF' },
  },
  {
    // blue
    light: { text: '#173E85', bg: '#E1EAFB', dot: '#2F6FE0' },
    dark: { text: '#A9C8FA', bg: '#142744', dot: '#5D97F2' },
  },
  {
    // magenta
    light: { text: '#7C1D55', bg: '#FBE4F1', dot: '#C93389' },
    dark: { text: '#F8A8D3', bg: '#3D1730', dot: '#F063B0' },
  },
  {
    // orange
    light: { text: '#7A3A03', bg: '#FBEBDD', dot: '#C05C09' },
    dark: { text: '#FBC38A', bg: '#3D2508', dot: '#F0964A' },
  },
  {
    // cyan
    light: { text: '#084C63', bg: '#DFF1F8', dot: '#0E7FA8' },
    dark: { text: '#97DDF2', bg: '#0E2A33', dot: '#4CC3EA' },
  },
  {
    // olive
    light: { text: '#3A430A', bg: '#F0F4D8', dot: '#6E830F' },
    dark: { text: '#D3E58A', bg: '#272E07', dot: '#B5CE3D' },
  },
  {
    // plum
    light: { text: '#571F6E', bg: '#F3E5F9', dot: '#9A3FBC' },
    dark: { text: '#E3B5F0', bg: '#331240', dot: '#C87CE0' },
  },
] as const

/**
 * Normalize a shop name to its color-identity key. `toLowerCase()` (not
 * `toLocaleLowerCase()`) — Unicode default case mapping, independent of the
 * user's locale, so the same name maps to the same color everywhere.
 */
export function normalizeShopName(name: string): string {
  return name.trim().normalize('NFC').toLowerCase()
}

export function shopColorIndex(name: string): number {
  return hashString(normalizeShopName(name)) % SHOP_PALETTE.length
}

export function shopColor(name: string): ShopColor {
  return SHOP_PALETTE[shopColorIndex(name)]
}

/**
 * Inline CSS-variable style for the `.shop-color` class hook (index.css) —
 * the rule resolves the light/dark pair per theme, so callers just spread
 * this onto the element.
 */
export function shopColorStyle(name: string): React.CSSProperties {
  const color = shopColor(name)
  return {
    '--sc-text-l': color.light.text,
    '--sc-bg-l': color.light.bg,
    '--sc-dot-l': color.light.dot,
    '--sc-text-d': color.dark.text,
    '--sc-bg-d': color.dark.bg,
    '--sc-dot-d': color.dark.dot,
  } as React.CSSProperties
}
