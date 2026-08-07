import { useCountUp } from '@/hooks/use-count-up'
import { useReducedMotion } from '@/hooks/use-reduced-motion'
import { formatDeltaPct } from '@/lib/format'
import type { DashboardSummary } from '@/lib/types'

/**
 * Summary tiles (#144): the STANDING tiles always show the GLOBAL summary
 * (a "health hub" that does not change as you type); when a search/filter
 * is active, the filtered summary surfaces as a small annotation on each
 * tile ("3 in this filter").
 */

function TileValue({ value, suffix }: { value: number; suffix?: string }) {
  const reducedMotion = useReducedMotion()
  const animated = useCountUp(value, reducedMotion)
  return (
    <span className="tabular-nums">
      {Math.round(animated)}
      {suffix}
    </span>
  )
}

interface SummaryTilesProps {
  global: DashboardSummary
  forCurrentQuery: DashboardSummary
  filterActive: boolean
}

export function SummaryTiles({ global, forCurrentQuery, filterActive }: SummaryTilesProps) {
  return (
    <section className="mb-6 grid grid-cols-1 gap-3 md:grid-cols-3" aria-label="Tracking summary">
      <div className="rounded-2xl border border-line bg-[linear-gradient(150deg,var(--iris-soft),var(--surface)_70%)] p-4 shadow-card">
        <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-ink-faint">
          <span className="size-[7px] rounded-full bg-iris" aria-hidden="true" />
          Tracking
        </div>
        <div className="mt-2 font-display text-[27px] font-bold leading-tight tracking-tight">
          <TileValue value={global.totalTracked} />{' '}
          <span className="text-[15px] font-semibold text-ink-muted">products</span>
        </div>
        {filterActive && (
          <div className="mt-0.5 text-[12.5px] text-ink-muted">
            {forCurrentQuery.totalTracked} in this filter
          </div>
        )}
      </div>

      <div className="rounded-2xl border border-line bg-[linear-gradient(150deg,var(--good-soft),var(--surface)_70%)] p-4 shadow-card">
        <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-ink-faint">
          <span className="size-[7px] rounded-full bg-good" aria-hidden="true" />
          Price drops · 7 days
        </div>
        <div className="mt-2 font-display text-[27px] font-bold leading-tight tracking-tight text-good-strong">
          <TileValue value={global.drops7d} />
        </div>
        {filterActive && (
          <div className="mt-0.5 text-[12.5px] text-ink-muted">
            {forCurrentQuery.drops7d} in this filter
          </div>
        )}
      </div>

      <div className="rounded-2xl border border-line bg-surface p-4 shadow-card">
        <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-ink-faint">
          <span className="size-[7px] rounded-full bg-good" aria-hidden="true" />
          Biggest drop
        </div>
        {global.biggestDrop !== null ? (
          <>
            <div className="mt-2 font-display text-[27px] font-bold leading-tight tracking-tight text-good-strong">
              ▼ <TileValue value={Math.round(Math.abs(global.biggestDrop.deltaPct))} suffix="%" />
            </div>
            <div className="mt-0.5 truncate text-[12.5px] text-ink-muted">
              <bdi>{global.biggestDrop.productName}</bdi>
              {filterActive && forCurrentQuery.biggestDrop !== null && (
                <> · {formatDeltaPct(forCurrentQuery.biggestDrop.deltaPct)} in this filter</>
              )}
            </div>
          </>
        ) : (
          <>
            <div className="mt-2 font-display text-[27px] font-bold leading-tight tracking-tight text-ink-faint">
              —
            </div>
            <div className="mt-0.5 text-[12.5px] text-ink-muted">No drops yet</div>
          </>
        )}
      </div>
    </section>
  )
}
