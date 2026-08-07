import type { PricePoint } from '@/lib/types'

/**
 * Sparkline geometry (#144) — pure math, kept out of the component so it is
 * unit-testable and the component file stays fast-refreshable.
 *
 * Plotted against TIME, not sample index (scrape history is irregular).
 * Degenerate cases:
 * - empty/invalid series → 'empty' (component renders a muted placeholder);
 * - zero TIME span (single point / identical timestamps) → 'dot' (a lone
 *   `M x y` path renders nothing — and no divide-by-zero on the X scale);
 * - zero PRICE range → flat midline, via the same Y-range floor that stops
 *   a trivial ₪99.99→₪100.00 wiggle from stretching to full height.
 */

export const SPARK_W = 66
export const SPARK_H = 26
const PAD_Y = 2
/** Minimum Y-range as a fraction of the max price (the volatility floor). */
const MIN_SPAN_FRACTION = 0.03

export interface SparklineGeometry {
  kind: 'empty' | 'dot' | 'line'
  path?: string
  dot?: { x: number; y: number }
  min: number
  max: number
}

export function sparklineGeometry(series: PricePoint[]): SparklineGeometry {
  const points = series
    .map((p) => ({ t: Date.parse(p.t), v: Number(p.price) }))
    .filter((p) => !Number.isNaN(p.t) && Number.isFinite(p.v))
    .sort((a, b) => a.t - b.t) // wire order may be newest-first — sort ascending

  if (points.length === 0) return { kind: 'empty', min: 0, max: 0 }

  const values = points.map((p) => p.v)
  const min = Math.min(...values)
  const max = Math.max(...values)

  const span = max - min
  const minSpan = Math.max(Math.abs(max) * MIN_SPAN_FRACTION, Number.EPSILON)
  const effectiveSpan = Math.max(span, minSpan)
  const mid = (max + min) / 2
  const lo = mid - effectiveSpan / 2
  const yOf = (v: number) => PAD_Y + (1 - (v - lo) / effectiveSpan) * (SPARK_H - 2 * PAD_Y)

  const minT = points[0].t
  const maxT = points[points.length - 1].t
  if (maxT === minT) {
    return { kind: 'dot', dot: { x: SPARK_W / 2, y: yOf(points[0].v) }, min, max }
  }

  const xOf = (t: number) => ((t - minT) / (maxT - minT)) * SPARK_W
  const path = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${xOf(p.t).toFixed(1)} ${yOf(p.v).toFixed(1)}`)
    .join(' ')
  return { kind: 'line', path, min, max }
}
