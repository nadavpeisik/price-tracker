/**
 * Frontend view-model + dashboard query contract for the tracked-items
 * dashboard (#144).
 *
 * `TrackedProduct` mirrors one row of the dashboard endpoint (#146:
 * availability counts, delta7d, normalized sparkline from #145) and
 * `Listing` mirrors one row of the listings endpoint (#157:
 * GET /api/products/{id}/listings — per-shop prices FX-normalized into the
 * display currency, already in display order). Both are the wire shape
 * field-for-field; the mock client implements the same contract so offline
 * work and live data are interchangeable.
 *
 * MONEY IS A DECIMAL STRING, never `number` — the backend uses
 * BigDecimal(19,4). The frontend does no money arithmetic; it parses to a
 * number only at the Intl.NumberFormat / sparkline-plot boundary.
 *
 * NULLABILITY IS REAL: the backend returns nulls for "no latest price",
 * "not convertible", "not checked yet" — the nullable fields mirror that.
 */

export type ListingAvailability = 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'

export interface AvailabilityRollup {
  /** MIXED drives the amber "N of M in stock" badge. */
  status: ListingAvailability | 'MIXED'
  availableCount: number
  /**
   * CAN BE 0: the backend allows a product with zero tracked items. Render
   * "No shops tracked" (not "0 of 0 in stock") and a neutral price
   * placeholder.
   */
  total: number
}

export interface PricePoint {
  /** ISO timestamp. */
  t: string
  /** Decimal string (BigDecimal on the wire). */
  price: string
}

/**
 * One shop's listing as the expanded panel shows it (#157). WIRE ORDER IS
 * DISPLAY ORDER: the backend sorts (not out of stock first, then converted
 * price ascending, unpriced last, ties by id) because only it holds exact
 * decimals and the FX-normalized amounts — the panel renders as received.
 */
export interface Listing {
  trackedItemId: number
  /** Nullable for hand-inserted legacy rows only; render a neutral fallback. */
  shopName: string | null
  /** Nullable for hand-inserted legacy rows only; safeExternalHref handles it. */
  url: string | null
  /**
   * The shop's own price. Null when the listing has NO CURRENT observation —
   * never scraped, or its latest record is older than the carry-forward TTL
   * (the same rule the row's "N of M in stock" applies) → "no current price".
   */
  priceOriginal: string | null
  /** ISO 4217; null when priceOriginal is null. */
  priceOriginalCurrency: string | null
  /**
   * The same amount in the display currency — what the panel compares. Null
   * when priceOriginal is null OR the amount is unconvertible (no FX snapshot
   * yet, unknown currency); the original survives so it can still be shown.
   */
  priceConverted: string | null
  priceConvertedCurrency: string | null
  /** FX rate used was over a week old → "Rate outdated". */
  conversionStale: boolean
  /** UNKNOWN when there is no current observation. */
  availability: ListingAvailability
  /**
   * The listing's own timestamp — populated even when the observation is too
   * old to count; "never" vs "9 days ago" is what tells the two apart.
   */
  lastChecked: string | null
  /**
   * OPTIONAL and UNFETCHED in v1 — per-shop mini-charts are a follow-up.
   * Comes from a separate per-item call (newest-first on the wire).
   */
  priceHistory?: PricePoint[]
}

export interface TrackedProduct {
  id: number
  name: string
  /** Backend image endpoint; null → gradient fallback avatar. */
  imageUrl: string | null
  /** Optional; drives the fallback avatar icon when present. */
  category?: string | null
  /**
   * Product-level rollup — consumed directly; the client never recomputes
   * best price or cross-currency history (backend owns FX + the rollup).
   */
  bestPriceConverted: string | null
  bestPriceConvertedCurrency: string | null
  bestPriceOriginal: string | null
  bestPriceOriginalCurrency: string | null
  bestPriceShop: string | null
  /**
   * The listing behind bestPriceShop — the panel marks it "Best" by identity,
   * never by position. Null together with the rest of the best-price cluster.
   */
  bestTrackedItemId: number | null
  /** FX snapshot was stale when computed → badge the converted price. */
  conversionStale: boolean
  /** Date of the FX snapshot used; null + stale → "Rate outdated" (no date). */
  conversionAsOf: string | null
  /** Listings span currencies → info flag (comparison still valid via FX). */
  mixedCurrencies: boolean
  availability: AvailabilityRollup
  /**
   * FX-normalized % vs the 7-days-ago baseline (nearest-earlier sample, no
   * interpolation). null → under 7d of history → `New` tag (STRICT null
   * check — a real 0 is a flat delta, not `New`).
   */
  delta7d: number | null
  /** FX-normalized, chronological product-level series (backend/mock-owned). */
  sparkline: PricePoint[]
  /**
   * SEPARATELY-LOADED on row expand — undefined until then. Optionality is
   * what makes the type system enforce the lazy fetch.
   */
  listings?: Listing[]
}

/* ── Dashboard query request + response envelope (backend-driven; #146) ── */

export const DASHBOARD_SORTS = ['name', 'lowestCurrentPrice', 'biggest7dDrop'] as const
export type DashboardSort = (typeof DASHBOARD_SORTS)[number]

/** UI state → serialized to backend params by the API client. */
export interface DashboardQuery {
  search?: string
  /** Multi-select; matched against facets.shops. */
  shops?: string[]
  sort: DashboardSort
  /**
   * 1-BASED, and stays that way on the wire (#146) — the backend rejects
   * `?page=0` and echoes the requested page back in `page.number`, so any
   * page number the client receives is one it can send.
   */
  page: number
  size: number
}

export interface DashboardPageMeta {
  /** 1-based at the UI boundary. */
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface BiggestDrop {
  productId: number
  productName: string
  deltaPct: number
}

export interface DashboardSummary {
  totalTracked: number
  drops7d: number
  /**
   * Enough to render + link the tile — the biggest-drop product may not be
   * on the current page, so the name travels with the id.
   */
  biggestDrop: BiggestDrop | null
}

export interface DashboardFacets {
  shops: string[]
}

export interface DashboardResponse {
  items: TrackedProduct[]
  page: DashboardPageMeta
  /** GLOBAL shop list for the filter chips — never derived from the page. */
  facets: DashboardFacets
  /** Whole tracked set → the standing tiles. */
  globalSummary: DashboardSummary
  /** Scoped to the active search/filter → annotation on the current view. */
  summaryForCurrentQuery: DashboardSummary
}
