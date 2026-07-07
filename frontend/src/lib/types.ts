/**
 * Frontend view-model + dashboard query contract for the tracked-items
 * dashboard (#144).
 *
 * This is NOT 1:1 with any backend DTO. It is a view model composed from
 * three real backend shapes (paged ProductSummaryResponse, detail
 * TrackedItemSummary listings, per-item PriceHistoryResponse) plus fields
 * only the future dashboard endpoint supplies (#146: availability counts,
 * delta7d, normalized sparkline from #145). The mock client implements this
 * exact contract so live wiring is a URL swap, not a logic rewrite.
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
  status: 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN' | 'MIXED'
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

export interface Listing {
  trackedItemId: number
  shop: string
  url: string
  /** Decimal string; null → "not checked yet" — neutral placeholder, never 0. */
  price: string | null
  /** ISO 4217 (e.g. "ILS"); null when price is null. */
  currency: string | null
  availability: ListingAvailability
  /** ISO timestamp; null → "never checked". */
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
  /** UI is 1-BASED; the API client translates to Spring's 0-based Pageable. */
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

export interface DashboardSummary {
  totalTracked: number
  drops7d: number
  /**
   * Enough to render + link the tile — the biggest-drop product may not be
   * on the current page, so the name travels with the id.
   */
  biggestDrop: { productId: number; productName: string; deltaPct: number } | null
}

export interface DashboardResponse {
  items: TrackedProduct[]
  page: DashboardPageMeta
  /** GLOBAL shop list for the filter chips — never derived from the page. */
  facets: { shops: string[] }
  /** Whole tracked set → the standing tiles. */
  globalSummary: DashboardSummary
  /** Scoped to the active search/filter → annotation on the current view. */
  summaryForCurrentQuery: DashboardSummary
}
