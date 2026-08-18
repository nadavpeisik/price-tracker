import { describe, expect, it } from 'vitest'
import { collectCelebrations, createCelebrationState } from '@/lib/celebration'
import type { TrackedProduct } from '@/lib/types'

const product = (id: number, price: string | null): TrackedProduct => ({
  id,
  name: `P${id}`,
  imageUrl: null,
  bestPriceConverted: price,
  bestPriceConvertedCurrency: price === null ? null : 'ILS',
  bestPriceOriginal: price,
  bestPriceOriginalCurrency: price === null ? null : 'ILS',
  bestPriceShop: price === null ? null : 'KSP',
  bestTrackedItemId: price === null ? null : 1001,
  conversionStale: false,
  conversionAsOf: null,
  mixedCurrencies: false,
  availability: { status: 'AVAILABLE', availableCount: 1, total: 1 },
  delta7d: -5,
  sparkline: [],
})

describe('collectCelebrations', () => {
  it('suppresses the first successful load entirely', () => {
    const state = createCelebrationState()
    expect(collectCelebrations(state, [product(1, '100'), product(2, '50')])).toEqual([])
  })

  it('celebrates a live drop observed between loads, keyed by product id', () => {
    const state = createCelebrationState()
    collectCelebrations(state, [product(1, '100'), product(2, '50')])
    expect(collectCelebrations(state, [product(1, '90'), product(2, '50')])).toEqual([1])
  })

  it('never re-celebrates the same price after a remount (pagination/filter)', () => {
    const state = createCelebrationState()
    collectCelebrations(state, [product(1, '100')])
    expect(collectCelebrations(state, [product(1, '90')])).toEqual([1])
    // Row disappears (other page) and comes back with the same price.
    collectCelebrations(state, [])
    expect(collectCelebrations(state, [product(1, '90')])).toEqual([])
  })

  it('ignores rises and first sightings of unseen products', () => {
    const state = createCelebrationState()
    collectCelebrations(state, [product(1, '100')])
    expect(collectCelebrations(state, [product(1, '110'), product(3, '10')])).toEqual([])
  })

  it('handles null prices without celebrating', () => {
    const state = createCelebrationState()
    collectCelebrations(state, [product(1, '100')])
    expect(collectCelebrations(state, [product(1, null)])).toEqual([])
    // Price returning later is a fresh sighting, not a drop.
    expect(collectCelebrations(state, [product(1, '80')])).toEqual([])
  })
})
