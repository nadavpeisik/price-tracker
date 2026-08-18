/**
 * Mock dashboard client (#144) — implements the SAME query contract as the
 * future backend endpoint (#146): DashboardQuery in → DashboardResponse out,
 * with server-side search/filter/sort/pagination and both summary flavors.
 * Swapping to the live endpoint is a URL change in api-client.ts, not a
 * logic rewrite.
 *
 * DEV-ONLY — imported solely behind `import.meta.env.DEV`; see mock-data.ts.
 */
import { buildMockDb, type MockDbEntry } from '@/mocks/mock-data'
import { foldShop } from '@/lib/shop-identity'
import type {
  DashboardQuery,
  DashboardResponse,
  DashboardSummary,
  Listing,
  TrackedProduct,
} from '@/lib/types'

/** Simulated network latency so loading states are visible in dev. */
const LATENCY_MS = 350

let db: MockDbEntry[] | null = null

function getDb(): MockDbEntry[] {
  // Built once per session so timestamps/deltas stay stable across refetches
  // (a fresh build per call would shift "now" and re-trigger celebrations).
  db ??= buildMockDb(Date.now())
  return db
}

/** Test hook: rebuild the catalog against a controlled clock. */
export function resetMockDb(now?: number): void {
  db = now === undefined ? null : buildMockDb(now)
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

function matches(entry: MockDbEntry, query: DashboardQuery): boolean {
  const search = query.search?.trim().toLowerCase()
  if (search && !entry.product.name.toLowerCase().includes(search)) return false
  if (query.shops && query.shops.length > 0) {
    // Fold BOTH sides, like the backend: a canonicalized "KSP" chip must still
    // match a listing spelled "ksp" (and vice versa).
    const shops = new Set(query.shops.map(foldShop))
    if (!entry.listings.some((l) => shops.has(foldShop(l.shopName)))) return false
  }
  return true
}

function compare(a: TrackedProduct, b: TrackedProduct, sort: DashboardQuery['sort']): number {
  switch (sort) {
    case 'name':
      return a.name.localeCompare(b.name)
    case 'lowestCurrentPrice': {
      // null price sorts LAST (#144).
      if (a.bestPriceConverted === null) return b.bestPriceConverted === null ? 0 : 1
      if (b.bestPriceConverted === null) return -1
      return Number(a.bestPriceConverted) - Number(b.bestPriceConverted)
    }
    case 'biggest7dDrop': {
      // Most negative delta first; null delta (`New`) sorts last.
      if (a.delta7d === null) return b.delta7d === null ? 0 : 1
      if (b.delta7d === null) return -1
      return a.delta7d - b.delta7d
    }
    default: {
      // A new sort variant becomes a compile error here, not a silent
      // "everything equal" at runtime.
      const exhaustive: never = sort
      return exhaustive
    }
  }
}

function summarize(entries: MockDbEntry[]): DashboardSummary {
  const drops = entries.filter((e) => e.product.delta7d !== null && e.product.delta7d < 0)
  const biggest = drops.reduce<MockDbEntry | null>(
    (best, e) => (best === null || e.product.delta7d! < best.product.delta7d! ? e : best),
    null,
  )
  return {
    totalTracked: entries.length,
    drops7d: drops.length,
    biggestDrop: biggest
      ? {
          productId: biggest.product.id,
          productName: biggest.product.name,
          deltaPct: biggest.product.delta7d!,
        }
      : null,
  }
}

export async function mockFetchDashboard(query: DashboardQuery): Promise<DashboardResponse> {
  await sleep(LATENCY_MS)
  const all = getDb()

  const filtered = all.filter((e) => matches(e, query))
  const sorted = filtered
    .slice()
    .sort((a, b) => compare(a.product, b.product, query.sort))

  const totalElements = sorted.length
  const totalPages = Math.ceil(totalElements / query.size)
  const start = (query.page - 1) * query.size
  const items = sorted.slice(start, start + query.size).map((e) => e.product)

  return {
    items,
    page: { number: query.page, size: query.size, totalElements, totalPages },
    facets: {
      // GLOBAL facet list — never derived from the current page. One chip per
      // folded identity, labelled by the first spelling seen (deterministic;
      // the backend's "most frequent spelling" rule is not reproduced here).
      shops: facetShops(all),
    },
    globalSummary: summarize(all),
    summaryForCurrentQuery: summarize(filtered),
  }
}

function facetShops(all: MockDbEntry[]): string[] {
  const labelByKey = new Map<string, string>()
  for (const entry of all) {
    for (const l of entry.listings) {
      const key = foldShop(l.shopName)
      if (key !== null && !labelByKey.has(key)) labelByKey.set(key, l.shopName!)
    }
  }
  return [...labelByKey.values()].sort((a, b) => a.localeCompare(b))
}

export async function mockFetchListings(productId: number): Promise<Listing[]> {
  await sleep(LATENCY_MS)
  const entry = getDb().find((e) => e.product.id === productId)
  if (!entry) throw new Error(`Unknown product id ${productId}`)
  // The mock plays the backend, which owns the panel order (#157): not out of
  // stock first, then converted price ascending, unpriced last, ties by id.
  // (Number() on money is fine HERE — DEV-only fixture code, not business
  // logic; the real ordering runs on exact BigDecimals server-side.)
  return entry.listings.slice().sort((a, b) => {
    const outA = a.availability === 'UNAVAILABLE' ? 1 : 0
    const outB = b.availability === 'UNAVAILABLE' ? 1 : 0
    if (outA !== outB) return outA - outB
    if (a.priceConverted === null || b.priceConverted === null) {
      if (a.priceConverted === b.priceConverted) return a.trackedItemId - b.trackedItemId
      return a.priceConverted === null ? 1 : -1
    }
    const byPrice = Number(a.priceConverted) - Number(b.priceConverted)
    return byPrice !== 0 ? byPrice : a.trackedItemId - b.trackedItemId
  })
}
