/**
 * Shop identity on the client (#157).
 *
 * The backend folds shop names case-insensitively (`ShopIdentity.of` =
 * trim + lower-case) and labels each facet with the most frequent stored
 * spelling — so `facets.shops` may say "KSP" while a bookmarked URL says
 * `?shop=ksp`. The filter itself works either way (the backend re-folds
 * whatever we send); what would break is CLIENT-SIDE exact matching — the
 * prune of unknown bookmarked shops and the ToggleGroup's chip selection.
 * This module is the one place that knows the fold, so both use the same one.
 *
 * `trim()`/`toLowerCase()` with no locale argument are locale-independent
 * and match Java's `Locale.ROOT` fold for every name the shop resolver
 * produces (ASCII / Hebrew). They are not formally identical on exotic
 * whitespace — that never occurs in resolver output, and a miss here only
 * affects chip highlighting, never the filter.
 */

/** Null for null/blank input, like the backend's `ShopIdentity.of`. */
export function foldShop(name: string | null | undefined): string | null {
  if (name === null || name === undefined) return null
  const folded = name.trim().toLowerCase()
  return folded === '' ? null : folded
}

export interface CanonicalShops {
  /** Bookmarked values re-spelled as their facet label, unknowns dropped, de-duplicated, order kept. */
  shops: string[]
  /**
   * True when a bookmarked shop had NO matching facet (a genuinely different
   * result set) — as opposed to a spelling/whitespace/duplicate rewrite, after
   * which the identity set is unchanged.
   */
  droppedUnknown: boolean
}

export function canonicalizeShops(bookmarked: readonly string[], facets: readonly string[]): CanonicalShops {
  const labelByKey = new Map<string, string>()
  for (const label of facets) {
    const key = foldShop(label)
    if (key !== null && !labelByKey.has(key)) labelByKey.set(key, label)
  }

  const shops: string[] = []
  const seen = new Set<string>()
  let droppedUnknown = false
  for (const raw of bookmarked) {
    const key = foldShop(raw)
    const label = key === null ? undefined : labelByKey.get(key)
    if (key === null || label === undefined) {
      droppedUnknown = true
      continue
    }
    if (seen.has(key)) continue
    seen.add(key)
    shops.push(label)
  }
  return { shops, droppedUnknown }
}

/** Element-wise equality — the effect that calls `canonicalizeShops` must not loop on an equal result. */
export function sameShops(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((shop, i) => shop === b[i])
}
