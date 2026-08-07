import type { AvailabilityRollup, ListingAvailability } from '@/lib/types'

/**
 * Availability badges (#144, consistent with #124's tri-state).
 *
 * Product level renders the ROLLUP: all in → green "In stock"; MIXED →
 * amber "N of M in stock"; UNAVAILABLE (red) only when every listing is
 * out; UNKNOWN → amber. `total === 0` is a real state (product with zero
 * tracked items) → neutral "No shops tracked", never "0 of 0 in stock".
 */

const DOT = 'inline-block size-1.5 rounded-full'
const BADGE =
  'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-1 text-[11.5px] font-semibold'

type Tone = 'good' | 'bad' | 'warn' | 'neutral'

const TONE_CLASSES: Record<Tone, { badge: string; dot: string }> = {
  good: { badge: 'bg-good-soft text-good-strong', dot: 'bg-good' },
  bad: { badge: 'bg-bad-soft text-bad', dot: 'bg-bad' },
  warn: { badge: 'bg-warn-soft text-warn', dot: 'bg-warn' },
  neutral: { badge: 'bg-surface-2 text-ink-muted', dot: 'bg-ink-faint' },
}

/** Shared dot + label pill, so the tri-state styling lives in one place. */
function StatusBadge({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  const c = TONE_CLASSES[tone]
  return (
    <span className={`${BADGE} ${c.badge}`}>
      <span className={`${DOT} ${c.dot}`} aria-hidden="true" />
      {children}
    </span>
  )
}

export function ProductAvailabilityBadge({ rollup }: { rollup: AvailabilityRollup }) {
  if (rollup.total === 0) {
    return <StatusBadge tone="neutral">No shops tracked</StatusBadge>
  }
  switch (rollup.status) {
    case 'AVAILABLE':
      return <StatusBadge tone="good">In stock</StatusBadge>
    case 'UNAVAILABLE':
      return <StatusBadge tone="bad">Out of stock</StatusBadge>
    case 'MIXED':
      return (
        <StatusBadge tone="warn">
          {rollup.availableCount} of {rollup.total} in stock
        </StatusBadge>
      )
    case 'UNKNOWN':
      return <StatusBadge tone="warn">Unknown</StatusBadge>
  }
}

/** Listing level shows the raw tri-state (no rollup). */
export function ListingAvailabilityBadge({ status }: { status: ListingAvailability }) {
  switch (status) {
    case 'AVAILABLE':
      return <StatusBadge tone="good">In stock</StatusBadge>
    case 'UNAVAILABLE':
      return <StatusBadge tone="bad">Out of stock</StatusBadge>
    case 'UNKNOWN':
      return <StatusBadge tone="warn">Unknown</StatusBadge>
  }
}
