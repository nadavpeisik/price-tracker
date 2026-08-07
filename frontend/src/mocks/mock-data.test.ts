import { describe, expect, it } from 'vitest'
import { buildMockDb, computeDelta7d } from '@/mocks/mock-data'
import type { PricePoint } from '@/lib/types'

/**
 * Baseline-rule fixtures (#144/#145 semantics): these tests are the
 * canonical frontend home of the 7-day delta definition — the UI renders
 * the supplied value and never recomputes it. Same-currency fixtures only;
 * cross-currency correctness is backend work (#145).
 */

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 6, 7, 12, 0, 0)

const point = (daysAgo: number, price: number): PricePoint => ({
  t: new Date(NOW - daysAgo * DAY).toISOString(),
  price: price.toFixed(2),
})

describe('computeDelta7d (baseline rule)', () => {
  it('uses the nearest-earlier sample at or before now − 7d (sparse history, no interpolation)', () => {
    // Points at 10d and 8d ago — baseline is the 8d point (nearest earlier),
    // NOT an interpolation between 8d and 6d.
    const series = [point(10, 200), point(8, 100), point(6, 150), point(0, 110)]
    expect(computeDelta7d(series, NOW)).toBeCloseTo(10) // (110-100)/100
  })

  it('includes a point at exactly now − 7d as the baseline', () => {
    const series = [point(10, 300), point(7, 100), point(1, 120)]
    expect(computeDelta7d(series, NOW)).toBeCloseTo(20)
  })

  it('returns null with under 7 days of history (→ New tag, never 0%)', () => {
    const series = [point(5, 100), point(2, 90), point(0.5, 80)]
    expect(computeDelta7d(series, NOW)).toBeNull()
  })

  it('returns exactly 0 for a flat week (0 is a real delta, not New)', () => {
    const series = [point(9, 100), point(3, 100), point(0.2, 100)]
    expect(computeDelta7d(series, NOW)).toBe(0)
  })

  it('returns null for an empty series', () => {
    expect(computeDelta7d([], NOW)).toBeNull()
  })
})

describe('mock module sentinel', () => {
  it('stamps the bundle-grep sentinel as a side effect on import', () => {
    // Guards the CI mock-gate: the sentinel must be attached via a side
    // effect (not just an unused const) or tree-shaking would remove it and
    // the dist grep could false-negative on a real leak.
    expect((globalThis as Record<string, unknown>).__PRICEHUNT_MOCK__).toBe(
      '__MOCK_DATA_SENTINEL__',
    )
  })
})

describe('buildMockDb', () => {
  const db = buildMockDb(NOW)
  const byName = (name: string) => db.find((e) => e.product.name === name)!

  it('supplies null delta + New semantics for the under-7d product', () => {
    expect(byName('Apple AirPods Pro 2 (USB-C)').product.delta7d).toBeNull()
  })

  it('supplies delta 0 (not null) for the flat product', () => {
    expect(byName('Dell UltraSharp U2723QE 4K').product.delta7d).toBe(0)
  })

  it('handles a missing current price: null best price, null delta', () => {
    const product = byName('Framework Laptop 16').product
    expect(product.bestPriceConverted).toBeNull()
    expect(product.delta7d).toBeNull()
  })

  it('models the zero-listings product with total 0 and an empty sparkline', () => {
    const entry = byName('Bambu Lab A1 mini')
    expect(entry.product.availability).toEqual({ status: 'UNKNOWN', availableCount: 0, total: 0 })
    expect(entry.product.sparkline).toHaveLength(0)
    expect(entry.listings).toHaveLength(0)
  })

  it('rolls availability up: MIXED with counts, UNAVAILABLE only when all out', () => {
    expect(byName('Dell UltraSharp U2723QE 4K').product.availability.status).toBe('MIXED')
    expect(byName('Apple AirPods Pro 2 (USB-C)').product.availability).toMatchObject({
      status: 'MIXED',
      availableCount: 1,
      total: 2,
    })
    expect(byName('Nintendo Switch 2').product.availability.status).toBe('UNAVAILABLE')
    expect(byName('Sony WH-1000XM5').product.availability.status).toBe('AVAILABLE')
  })

  it('keeps sparklines chronological (ascending timestamps)', () => {
    for (const entry of db) {
      const times = entry.product.sparkline.map((p) => Date.parse(p.t))
      const sorted = [...times].sort((a, b) => a - b)
      expect(times).toEqual(sorted)
    }
  })

  it('flags the mixed-currency product with a stale conversion', () => {
    const product = byName('מקלדת Keychron K8 Pro').product
    expect(product.mixedCurrencies).toBe(true)
    expect(product.conversionStale).toBe(true)
    expect(product.conversionAsOf).toBeNull()
  })

  it('keeps the mixed-currency best price internally coherent (no cross-currency raw compare)', () => {
    // Regression: previously the builder raw-compared prices across
    // currencies, picking Amazon's $102 and labelling it as ILS. Now the
    // converted best (₪382) and its native original ($102 USD) are distinct
    // and the sparkline is in the display (ILS) domain, ending near ₪382.
    const product = byName('מקלדת Keychron K8 Pro').product
    expect(product.bestPriceShop).toBe('Amazon')
    expect(product.bestPriceConverted).toBe('382.00')
    expect(product.bestPriceConvertedCurrency).toBe('ILS')
    expect(product.bestPriceOriginal).toBe('102.00')
    expect(product.bestPriceOriginalCurrency).toBe('USD')
    // Converted currency differs from original → the "at source" label shows.
    expect(product.bestPriceConvertedCurrency).not.toBe(product.bestPriceOriginalCurrency)
    // Sparkline is the normalized ILS series, not the raw USD number.
    expect(Number(product.sparkline.at(-1)!.price)).toBeCloseTo(382, 0)
  })

  it('derives the best from the cheapest listing for same-currency products', () => {
    const sony = byName('Sony WH-1000XM5').product
    // Ivory's ₪1279 is the cheapest across the three ILS listings.
    expect(sony.bestPriceShop).toBe('Ivory')
    expect(sony.bestPriceConvertedCurrency).toBe('ILS')
    expect(sony.bestPriceConverted).toBe(sony.bestPriceOriginal)
  })
})
