import { useQuery } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import { listingsQueryOptions } from '@/lib/queries'
import { formatPrice, formatRelativeTime } from '@/lib/format'
import { safeExternalHref } from '@/lib/safe-url'
import { shopColorStyle } from '@/lib/shop-colors'
import { useNow } from '@/hooks/use-now'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import { ListingAvailabilityBadge } from '@/components/dashboard/AvailabilityBadge'
import type { Listing } from '@/lib/types'

/**
 * Expanded per-shop listings (#144) — loaded LAZILY on row expand, in its
 * own query with its OWN row-level loading/error/retry (a detail-fetch
 * failure must never bubble to a page-level boundary or leave a blank
 * panel). This component is the only ticker consumer, so collapsed rows
 * never re-render on the minute tick.
 */

function ListingRow({ listing, isBest, now }: { listing: Listing; isBest: boolean; now: number }) {
  const price = formatPrice(listing.price, listing.currency)
  const href = safeExternalHref(listing.url)

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-dashed border-line-strong px-4.5 py-2.5 ps-7 md:grid md:grid-cols-[minmax(150px,1fr)_120px_110px_130px_90px]">
      <span className="shop-color inline-flex min-w-0 items-center gap-1.5 text-[13px] font-semibold" style={shopColorStyle(listing.shop)}>
        <span className="inline-block size-2 flex-none rounded-full bg-(--sc-dot)" aria-hidden="true" />
        <bdi className="truncate text-(--sc-text)">{listing.shop}</bdi>
        {isBest && (
          <span className="ms-0.5 rounded-[5px] bg-good px-1.5 py-px text-[9.5px] font-extrabold uppercase tracking-wider text-white">
            Best
          </span>
        )}
      </span>
      <span className="font-num text-[14.5px] font-semibold tabular-nums md:text-right">
        {price !== null ? <bdi>{price}</bdi> : <span className="font-sans text-xs font-normal text-ink-faint">not checked yet</span>}
      </span>
      <span className="md:justify-self-start">
        <ListingAvailabilityBadge status={listing.availability} />
      </span>
      <span className="text-xs text-ink-muted">{formatRelativeTime(listing.lastChecked, now)}</span>
      {href !== null ? (
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 justify-self-end rounded-lg border border-line-strong bg-surface px-2 py-1 text-xs font-semibold text-iris-strong transition-colors hover:border-iris"
        >
          Open
          <ExternalLink className="size-3" aria-hidden="true" />
          <span className="sr-only"> {listing.shop} in a new tab</span>
        </a>
      ) : (
        // Unsafe/malformed scraped URL — a non-interactive element, not a
        // dead link (no href="#" a11y trap).
        <span className="inline-flex items-center justify-self-end px-2 py-1 text-xs text-ink-faint" title="Link unavailable">
          Open
        </span>
      )}
    </div>
  )
}

export function ListingPanel({ productId, open }: { productId: number; open: boolean }) {
  const { data, status, refetch, isRefetching } = useQuery(listingsQueryOptions(productId, open))
  const now = useNow()

  if (status === 'pending') {
    return (
      <div className="space-y-2 px-4.5 py-3 ps-7">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-6 w-1/2" />
      </div>
    )
  }

  if (status === 'error') {
    // The alert region stays text-only (WAI-ARIA: interactive controls inside
    // an alert get announced inconsistently by assistive tech); the Retry
    // button is a sibling, not a descendant.
    return (
      <div className="flex items-center gap-3 px-4.5 py-3 ps-7 text-sm text-ink-muted">
        <span role="alert">Couldn't load listings.</span>
        <Button size="sm" variant="outline" onClick={() => void refetch()} disabled={isRefetching}>
          Retry
        </Button>
      </div>
    )
  }

  if (data.length === 0) {
    // Zero tracked items is a real, valid state (#144).
    return <div className="px-4.5 py-3 ps-7 text-sm text-ink-muted">No shops tracked for this product yet.</div>
  }

  // Data arrives cheapest-first; the first priced listing is the best.
  const bestId = data.find((l) => l.price !== null)?.trackedItemId

  return (
    <div>
      {data.map((listing) => (
        <ListingRow
          key={listing.trackedItemId}
          listing={listing}
          isBest={listing.trackedItemId === bestId}
          now={now}
        />
      ))}
    </div>
  )
}
