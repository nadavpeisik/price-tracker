import { useMemo } from 'react'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { formatPrice } from '@/lib/format'
import { SPARK_H, SPARK_W, sparklineGeometry } from '@/lib/sparkline-geometry'
import type { PricePoint } from '@/lib/types'

/**
 * Time-plotted price sparkline (#144). Geometry (and all its degenerate-case
 * guards) lives in lib/sparkline-geometry.ts; this component adds the
 * responsive SVG shell, the portal tooltip, and the sr-only mirror.
 */

interface SparklineProps {
  series: PricePoint[]
  currency: string | null
  /** Semantic stroke tone, driven by the product delta. */
  tone: 'good' | 'bad' | 'flat'
}

const TONE_CLASS = {
  good: 'text-good',
  bad: 'text-bad',
  flat: 'text-ink-faint',
} as const

export function Sparkline({ series, currency, tone }: SparklineProps) {
  const geometry = useMemo(() => sparklineGeometry(series), [series])

  if (geometry.kind === 'empty') {
    return <span className="text-[11px] text-ink-faint">no price history yet</span>
  }

  const minLabel = formatPrice(String(geometry.min), currency) ?? String(geometry.min)
  const maxLabel = formatPrice(String(geometry.max), currency) ?? String(geometry.max)
  const rangeLabel =
    geometry.min === geometry.max
      ? `Price history: steady at ${minLabel}`
      : `Price history: between ${minLabel} and ${maxLabel}`

  return (
    <Tooltip>
      {/* Trigger is a span (asChild) — it must stay a DOM SIBLING of the
          row's expand button, never a descendant (invalid HTML). The z-10
          lifts it above the button's stretched click overlay. */}
      <TooltipTrigger asChild>
        <span tabIndex={0} className={`relative z-10 block w-[66px] ${TONE_CLASS[tone]}`}>
          <svg
            viewBox={`0 0 ${SPARK_W} ${SPARK_H}`}
            preserveAspectRatio="none"
            className="block h-[26px] w-full overflow-visible"
            aria-hidden="true"
          >
            {geometry.kind === 'line' ? (
              <path
                d={geometry.path}
                fill="none"
                stroke="currentColor"
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
                vectorEffect="non-scaling-stroke"
              />
            ) : (
              <circle cx={geometry.dot!.x} cy={geometry.dot!.y} r={2.5} fill="currentColor" />
            )}
          </svg>
          {/* Hover-only tooltips can't be the sole channel — mirror for AT. */}
          <span className="sr-only">{rangeLabel}</span>
        </span>
      </TooltipTrigger>
      {/* Portals to the body (shadcn/Radix) so row overflow never clips it. */}
      <TooltipContent>
        {geometry.min === geometry.max ? minLabel : `${minLabel} – ${maxLabel}`}
      </TooltipContent>
    </Tooltip>
  )
}
