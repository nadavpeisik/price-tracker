import { motion } from 'motion/react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useReducedMotion } from '@/hooks/use-reduced-motion'

/**
 * Loading skeletons + the two DISTINCT empty states (#144):
 * (a) zero products tracked — home of the rocket illustration (static /
 *     gently floating; the full launch animation is the separate first-run
 *     intro and never re-runs on every empty visit);
 * (b) search/filter with no matches — lightweight, with a clear-filters
 *     action.
 */

export function SkeletonRows({ rows = 6 }: { rows?: number }) {
  return (
    <div aria-hidden="true">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3 border-b border-line px-4.5 py-3.5 last:border-b-0">
          <Skeleton className="size-[38px] rounded-[11px]" />
          <div className="min-w-0 flex-1 space-y-1.5">
            <Skeleton className="h-3.5 w-2/5" />
            <Skeleton className="h-3 w-1/4" />
          </div>
          <Skeleton className="hidden h-6 w-16 md:block" />
          <Skeleton className="h-5 w-14 rounded-lg" />
          <Skeleton className="h-6 w-24 rounded-full" />
        </div>
      ))}
    </div>
  )
}

export function ZeroTrackedState() {
  const reducedMotion = useReducedMotion()
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-16 text-center">
      <motion.span
        className="text-6xl"
        aria-hidden="true"
        animate={reducedMotion ? undefined : { y: [0, -8, 0] }}
        transition={{ duration: 3, repeat: Infinity, ease: 'easeInOut' }}
      >
        🚀
      </motion.span>
      <h2 className="font-display text-xl font-bold">Nothing tracked yet</h2>
      <p className="max-w-sm text-sm text-ink-muted">
        Track a product to start hunting prices across shops — we'll watch for drops so you don't
        have to.
      </p>
      <Button className="mt-1">+ Track a product</Button>
    </div>
  )
}

export function NoMatchesState({ onClear }: { onClear: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-12 text-center">
      <p className="text-sm text-ink-muted">No products match your search or filters.</p>
      <Button variant="outline" onClick={onClear}>
        Clear filters
      </Button>
    </div>
  )
}

export function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-12 text-center" role="alert">
      <p className="text-sm text-ink-muted">Couldn't load your tracked products.</p>
      <Button variant="outline" onClick={onRetry}>
        Try again
      </Button>
    </div>
  )
}
