import { describe, expect, it } from 'vitest'
import {
  normalizeShopName,
  SHOP_PALETTE,
  shopColor,
  shopColorIndex,
} from '@/lib/shop-colors'

/** WCAG relative luminance + contrast ratio (sRGB). */
function luminance(hex: string): number {
  const value = hex.replace('#', '')
  const [r, g, b] = [0, 2, 4].map((i) => {
    const channel = parseInt(value.slice(i, i + 2), 16) / 255
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const LIGHT_SURFACE = '#FFFFFF'
const DARK_SURFACE = '#201C15'

describe('shop color determinism', () => {
  it('normalizes case, padding, and Unicode form to one identity', () => {
    expect(normalizeShopName('KSP')).toBe('ksp')
    expect(shopColorIndex('KSP')).toBe(shopColorIndex('ksp '))
    expect(shopColorIndex('KSP')).toBe(shopColorIndex('Ksp'))
    expect(shopColorIndex(' KSP  ')).toBe(shopColorIndex('ksp'))
  })

  it('treats NFC and NFD spellings of the same Hebrew name as one shop', () => {
    // "בּאג" with the dagesh precomposed (NFC) vs decomposed (NFD).
    const nfc = 'בּאג'.normalize('NFC')
    const nfd = 'בּאג'.normalize('NFD')
    expect(shopColorIndex(nfc)).toBe(shopColorIndex(nfd))
  })

  it('is stable across calls and always lands inside the palette', () => {
    for (const name of ['KSP', 'Ivory', 'Bug', 'TMS', 'אלקטרה', 'Amazon', 'shop-that-does-not-exist-yet']) {
      const index = shopColorIndex(name)
      expect(index).toBeGreaterThanOrEqual(0)
      expect(index).toBeLessThan(SHOP_PALETTE.length)
      expect(shopColorIndex(name)).toBe(index)
      expect(shopColor(name)).toBe(SHOP_PALETTE[index])
    }
  })
})

describe('shop palette accessibility (WCAG AA)', () => {
  it.each(SHOP_PALETTE.map((entry, i) => [i, entry] as const))(
    'palette entry %i keeps chip text and dot legible in both themes',
    (_i, entry) => {
      // Chip text on its soft background — normal-size text needs 4.5:1.
      expect(contrast(entry.light.text, entry.light.bg)).toBeGreaterThanOrEqual(4.5)
      expect(contrast(entry.dark.text, entry.dark.bg)).toBeGreaterThanOrEqual(4.5)
      // Accent dot against the theme surface — non-text UI needs 3:1.
      expect(contrast(entry.light.dot, LIGHT_SURFACE)).toBeGreaterThanOrEqual(3)
      expect(contrast(entry.dark.dot, DARK_SURFACE)).toBeGreaterThanOrEqual(3)
    },
  )
})
