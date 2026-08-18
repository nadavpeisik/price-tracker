import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockFetchDashboard, mockFetchListings, resetMockDb } from '@/mocks/mock-client'
import type { DashboardQuery } from '@/lib/types'

/**
 * The mock client implements the SAME query contract as #146 — these tests
 * pin the server-side behaviors the UI relies on (search, shop filter,
 * sorts with null-last, pagination meta, global facets, both summaries).
 */

const NOW = Date.UTC(2026, 6, 7, 12, 0, 0)
const base: DashboardQuery = { sort: 'name', page: 1, size: 20 }

async function fetch(query: Partial<DashboardQuery>) {
  const promise = mockFetchDashboard({ ...base, ...query })
  await vi.runAllTimersAsync() // skip the simulated latency
  return promise
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  resetMockDb(NOW)
})

afterEach(() => {
  vi.useRealTimers()
  resetMockDb()
})

describe('mockFetchDashboard', () => {
  it('searches by product name, case-insensitively', async () => {
    const response = await fetch({ search: 'sony' })
    expect(response.items.map((p) => p.name)).toEqual(['Sony WH-1000XM5'])
    expect(response.page.totalElements).toBe(1)
  })

  it('filters by shop (product matches when ANY listing is in a selected shop)', async () => {
    const response = await fetch({ shops: ['TMS'] })
    for (const item of response.items) {
      expect(['LG C3 55" OLED evo TV', 'Dell UltraSharp U2723QE 4K', 'Framework Laptop 16']).toContain(
        item.name,
      )
    }
    expect(response.page.totalElements).toBe(3)
  })

  it('sorts by lowest price with null prices LAST', async () => {
    const response = await fetch({ sort: 'lowestCurrentPrice', size: 100 })
    const prices = response.items.map((p) =>
      p.bestPriceConverted === null ? null : Number(p.bestPriceConverted),
    )
    const nonNull = prices.filter((p): p is number => p !== null)
    expect(nonNull).toEqual([...nonNull].sort((a, b) => a - b))
    // Every null comes after every number.
    const firstNull = prices.indexOf(null)
    expect(firstNull).toBeGreaterThan(0)
    expect(prices.slice(firstNull).every((p) => p === null)).toBe(true)
  })

  it('sorts by biggest drop (most negative first) with null deltas LAST', async () => {
    const response = await fetch({ sort: 'biggest7dDrop', size: 100 })
    const deltas = response.items.map((p) => p.delta7d)
    const nonNull = deltas.filter((d): d is number => d !== null)
    expect(nonNull).toEqual([...nonNull].sort((a, b) => a - b))
    const firstNull = deltas.indexOf(null)
    expect(deltas.slice(firstNull).every((d) => d === null)).toBe(true)
  })

  it('paginates with 1-based meta and exposes GLOBAL facets on every page', async () => {
    const page1 = await fetch({ page: 1, size: 5 })
    const page2 = await fetch({ page: 2, size: 5 })
    expect(page1.page).toMatchObject({ number: 1, size: 5 })
    expect(page1.page.totalPages).toBe(Math.ceil(page1.page.totalElements / 5))
    expect(page2.items[0]?.id).not.toBe(page1.items[0]?.id)
    // Facets stay global even on a filtered/paged view.
    const filtered = await fetch({ search: 'sony' })
    expect(filtered.facets.shops).toEqual(page1.facets.shops)
    expect(filtered.facets.shops).toContain('אלקטרה')
  })

  it('returns a global summary that ignores the filter and a scoped one that respects it', async () => {
    const response = await fetch({ search: 'sony' })
    expect(response.globalSummary.totalTracked).toBeGreaterThan(1)
    expect(response.summaryForCurrentQuery.totalTracked).toBe(1)
    expect(response.globalSummary.biggestDrop).not.toBeNull()
  })
})

describe('mockFetchListings', () => {
  async function listings(productId: number) {
    const promise = mockFetchListings(productId)
    await vi.runAllTimersAsync()
    return promise
  }

  it('orders listings like the backend: not out of stock first, converted price ascending, unpriced last', async () => {
    // Sony (id 1) has three priced ILS listings — cheapest converted first.
    const result = await listings(1)
    const prices = result.map((l) => (l.priceConverted === null ? null : Number(l.priceConverted)))
    const nonNull = prices.filter((p): p is number => p !== null)
    expect(nonNull).toEqual([...nonNull].sort((a, b) => a - b))
    // Any null-priced listing sorts after every priced one.
    const firstNull = prices.indexOf(null)
    if (firstNull !== -1) expect(prices.slice(firstNull).every((p) => p === null)).toBe(true)
  })

  it('puts out-of-stock listings last even when they are the cheapest', async () => {
    // AirPods (id 5) has one AVAILABLE and one UNAVAILABLE listing.
    const result = await listings(5)
    const availability = result.map((l) => l.availability)
    const firstOut = availability.indexOf('UNAVAILABLE')
    expect(firstOut).toBeGreaterThan(0)
    expect(availability.slice(firstOut).every((a) => a === 'UNAVAILABLE')).toBe(true)
  })

  it('shop filter folds both sides — a canonical chip still matches a differently-cased request', async () => {
    const canonical = await fetch({ shops: ['TMS'] })
    const lower = await fetch({ shops: [' tms '] })
    expect(lower.items.map((p) => p.id)).toEqual(canonical.items.map((p) => p.id))
    expect(canonical.page.totalElements).toBe(3)
  })

  it('facets are one chip per folded shop identity', async () => {
    const response = await fetch({})
    const folded = response.facets.shops.map((s) => s.trim().toLowerCase())
    expect(new Set(folded).size).toBe(folded.length)
  })

  it('rejects for an unknown product id (drives the row-level error/retry UX)', async () => {
    // Attach the rejection expectation BEFORE advancing the fake timers, so
    // the promise never rejects while unhandled (which Vitest fails the run
    // over) — the shared `listings` helper awaits timers before returning.
    const pending = mockFetchListings(-1)
    const assertion = expect(pending).rejects.toThrow('Unknown product id')
    await vi.runAllTimersAsync()
    await assertion
  })
})
