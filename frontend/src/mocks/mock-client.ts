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
    const shops = new Set(query.shops)
    if (!entry.listings.some((l) => shops.has(l.shop))) return false
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
      // GLOBAL facet list — never derived from the current page.
      shops: [...new Set(all.flatMap((e) => e.listings.map((l) => l.shop)))].sort((a, b) =>
        a.localeCompare(b),
      ),
    },
    globalSummary: summarize(all),
    summaryForCurrentQuery: summarize(filtered),
  }
}

export async function mockFetchListings(productId: number): Promise<Listing[]> {
  await sleep(LATENCY_MS)
  const entry = getDb().find((e) => e.product.id === productId)
  if (!entry) throw new Error(`Unknown product id ${productId}`)
  // Cheapest-first, like the mockup's expanded view.
  return entry.listings.slice().sort((a, b) => {
    if (a.price === null) return b.price === null ? 0 : 1
    if (b.price === null) return -1
    return Number(a.price) - Number(b.price)
  })
}
