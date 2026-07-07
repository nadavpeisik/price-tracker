import { describe, expect, it } from 'vitest'
import { SPARK_H, SPARK_W, sparklineGeometry } from '@/lib/sparkline-geometry'
import type { PricePoint } from '@/lib/types'

const at = (iso: string, price: number): PricePoint => ({ t: iso, price: price.toFixed(2) })

describe('sparklineGeometry', () => {
  it('returns empty for an empty series (no NaN coordinates, no broken path)', () => {
    expect(sparklineGeometry([]).kind).toBe('empty')
  })

  it('returns a centered dot for a single point (zero time span)', () => {
    const geometry = sparklineGeometry([at('2026-07-01T00:00:00Z', 100)])
    expect(geometry.kind).toBe('dot')
    expect(geometry.dot!.x).toBe(SPARK_W / 2)
    expect(Number.isFinite(geometry.dot!.y)).toBe(true)
  })

  it('returns a dot when all points share one timestamp', () => {
    const t = '2026-07-01T00:00:00Z'
    expect(sparklineGeometry([at(t, 100), at(t, 100)]).kind).toBe('dot')
  })

  it('renders a flat midline for a zero price range', () => {
    const geometry = sparklineGeometry([
      at('2026-07-01T00:00:00Z', 100),
      at('2026-07-03T00:00:00Z', 100),
      at('2026-07-05T00:00:00Z', 100),
    ])
    expect(geometry.kind).toBe('line')
    // Every Y coordinate equals the vertical midpoint.
    const ys = [...geometry.path!.matchAll(/[ML][\d.]+ ([\d.]+)/g)].map((m) => Number(m[1]))
    expect(ys.every((y) => Math.abs(y - SPARK_H / 2) < 0.11)).toBe(true)
  })

  it('floors the Y-range so a trivial fluctuation does not read as volatility', () => {
    // 99.99 → 100.00 — the wiggle must occupy only a sliver of the height.
    const geometry = sparklineGeometry([
      at('2026-07-01T00:00:00Z', 99.99),
      at('2026-07-05T00:00:00Z', 100.0),
    ])
    const ys = [...geometry.path!.matchAll(/[ML][\d.]+ ([\d.]+)/g)].map((m) => Number(m[1]))
    const spread = Math.max(...ys) - Math.min(...ys)
    expect(spread).toBeLessThan(SPARK_H * 0.05)
  })

  it('plots against TIME, not sample index', () => {
    // Three points: two close together in time, one far — the X gaps must be
    // unequal even though the sample gaps are equal.
    const geometry = sparklineGeometry([
      at('2026-07-01T00:00:00Z', 100),
      at('2026-07-02T00:00:00Z', 105),
      at('2026-07-08T00:00:00Z', 110),
    ])
    const xs = [...geometry.path!.matchAll(/[ML]([\d.]+) /g)].map((m) => Number(m[1]))
    expect(xs[1] - xs[0]).toBeLessThan(xs[2] - xs[1])
  })

  it('sorts a newest-first wire series before plotting', () => {
    const geometry = sparklineGeometry([
      at('2026-07-08T00:00:00Z', 110),
      at('2026-07-01T00:00:00Z', 100),
    ])
    const xs = [...geometry.path!.matchAll(/[ML]([\d.]+) /g)].map((m) => Number(m[1]))
    expect(xs[0]).toBe(0)
    expect(xs[1]).toBe(SPARK_W)
  })
})
