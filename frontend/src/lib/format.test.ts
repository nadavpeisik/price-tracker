import { describe, expect, it } from 'vitest'
import { formatDeltaPct, formatPrice, formatRelativeTime } from '@/lib/format'

describe('formatPrice', () => {
  it('formats per the value currency via Intl (no hardcoded symbols)', () => {
    // Exact symbol/grouping is locale-dependent; assert the currency marker
    // and the digits are present rather than a byte-exact string.
    const ils = formatPrice('1279.00', 'ILS')!
    expect(ils).toMatch(/1,?279/)
    const usd = formatPrice('109.00', 'USD')!
    expect(usd).toContain('$')
  })

  it('returns null for missing price or currency (neutral placeholder, never 0)', () => {
    expect(formatPrice(null, 'ILS')).toBeNull()
    expect(formatPrice('10.00', null)).toBeNull()
    expect(formatPrice('not-a-number', 'ILS')).toBeNull()
  })

  it('degrades gracefully for an invalid currency code', () => {
    expect(formatPrice('10.00', 'NOPE!')).toBe('10.00 NOPE!')
  })
})

describe('formatDeltaPct', () => {
  it('renders magnitude with one decimal', () => {
    expect(formatDeltaPct(-6.04)).toBe('6.0%')
    expect(formatDeltaPct(12.35)).toBe('12.3%')
  })
})

describe('formatRelativeTime', () => {
  const now = Date.UTC(2026, 6, 7, 12, 0, 0)
  const ago = (ms: number) => new Date(now - ms).toISOString()

  it('formats minute/hour/day buckets', () => {
    expect(formatRelativeTime(ago(30_000), now)).toBe('checked just now')
    expect(formatRelativeTime(ago(5 * 60_000), now)).toBe('checked 5m ago')
    expect(formatRelativeTime(ago(3 * 3_600_000), now)).toBe('checked 3h ago')
    expect(formatRelativeTime(ago(49 * 3_600_000), now)).toBe('checked 2d ago')
  })

  it('clamps client/server clock drift to "just now"', () => {
    expect(formatRelativeTime(ago(-120_000), now)).toBe('checked just now')
  })

  it('renders null as never checked', () => {
    expect(formatRelativeTime(null, now)).toBe('never checked')
    expect(formatRelativeTime('garbage', now)).toBe('never checked')
  })
})
