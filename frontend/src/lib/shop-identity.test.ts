import { describe, expect, it } from 'vitest'
import { canonicalizeShops, foldShop, sameShops } from '@/lib/shop-identity'

describe('foldShop', () => {
  it('trims and lower-cases, like the backend ShopIdentity.of', () => {
    expect(foldShop(' KSP ')).toBe('ksp')
    expect(foldShop('Ksp')).toBe('ksp')
    expect(foldShop('אלקטרה')).toBe('אלקטרה')
  })

  it('is null for null, undefined and blank input', () => {
    expect(foldShop(null)).toBeNull()
    expect(foldShop(undefined)).toBeNull()
    expect(foldShop('   ')).toBeNull()
  })
})

describe('canonicalizeShops', () => {
  const facets = ['Amazon', 'KSP', 'אלקטרה']

  it('re-spells a bookmarked value as its facet label', () => {
    expect(canonicalizeShops(['ksp'], facets)).toEqual({ shops: ['KSP'], droppedUnknown: false })
    expect(canonicalizeShops([' KSP '], facets)).toEqual({ shops: ['KSP'], droppedUnknown: false })
  })

  it('de-duplicates by identity and keeps the first occurrence order', () => {
    expect(canonicalizeShops(['ksp', 'Amazon', 'KSP'], facets)).toEqual({
      shops: ['KSP', 'Amazon'],
      droppedUnknown: false,
    })
  })

  it('drops unknown shops and says so — that is a genuinely different result set', () => {
    expect(canonicalizeShops(['ksp', 'Bug'], facets)).toEqual({ shops: ['KSP'], droppedUnknown: true })
  })

  it('drops null-ish and whitespace-only values as unknown', () => {
    expect(canonicalizeShops(['   ', 'KSP'], facets)).toEqual({ shops: ['KSP'], droppedUnknown: true })
  })

  it('leaves already-canonical input untouched', () => {
    expect(canonicalizeShops(['KSP', 'Amazon'], facets)).toEqual({
      shops: ['KSP', 'Amazon'],
      droppedUnknown: false,
    })
  })

  it('facets that fold to the same key keep the first label', () => {
    expect(canonicalizeShops(['ksp'], ['KSP', 'ksp'])).toEqual({ shops: ['KSP'], droppedUnknown: false })
  })
})

describe('sameShops', () => {
  it('compares element-wise', () => {
    expect(sameShops(['KSP'], ['KSP'])).toBe(true)
    expect(sameShops(['KSP'], ['ksp'])).toBe(false)
    expect(sameShops(['KSP', 'Bug'], ['KSP'])).toBe(false)
    expect(sameShops([], [])).toBe(true)
  })
})
