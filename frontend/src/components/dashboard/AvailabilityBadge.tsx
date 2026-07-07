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

export function ProductAvailabilityBadge({ rollup }: { rollup: AvailabilityRollup }) {
  if (rollup.total === 0) {
    return (
      <span className={`${BADGE} bg-surface-2 text-ink-muted`}>
        <span className={`${DOT} bg-ink-faint`} aria-hidden="true" />
        No shops tracked
      </span>
    )
  }
  switch (rollup.status) {
    case 'AVAILABLE':
      return (
        <span className={`${BADGE} bg-good-soft text-good-strong`}>
          <span className={`${DOT} bg-good`} aria-hidden="true" />
          In stock
        </span>
      )
    case 'UNAVAILABLE':
      return (
        <span className={`${BADGE} bg-bad-soft text-bad`}>
          <span className={`${DOT} bg-bad`} aria-hidden="true" />
          Out of stock
        </span>
      )
    case 'MIXED':
      return (
        <span className={`${BADGE} bg-warn-soft text-warn`}>
          <span className={`${DOT} bg-warn`} aria-hidden="true" />
          {rollup.availableCount} of {rollup.total} in stock
        </span>
      )
    case 'UNKNOWN':
      return (
        <span className={`${BADGE} bg-warn-soft text-warn`}>
          <span className={`${DOT} bg-warn`} aria-hidden="true" />
          Unknown
        </span>
      )
  }
}

/** Listing level shows the raw tri-state (no rollup). */
export function ListingAvailabilityBadge({ status }: { status: ListingAvailability }) {
  switch (status) {
    case 'AVAILABLE':
      return (
        <span className={`${BADGE} bg-good-soft text-good-strong`}>
          <span className={`${DOT} bg-good`} aria-hidden="true" />
          In stock
        </span>
      )
    case 'UNAVAILABLE':
      return (
        <span className={`${BADGE} bg-bad-soft text-bad`}>
          <span className={`${DOT} bg-bad`} aria-hidden="true" />
          Out of stock
        </span>
      )
    case 'UNKNOWN':
      return (
        <span className={`${BADGE} bg-warn-soft text-warn`}>
          <span className={`${DOT} bg-warn`} aria-hidden="true" />
          Unknown
        </span>
      )
  }
}
