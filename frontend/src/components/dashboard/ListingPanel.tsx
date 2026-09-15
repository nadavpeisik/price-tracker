import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, Eye, EyeOff } from 'lucide-react'
import { listingsQueryOptions } from '@/lib/queries'
import { setListingHidden } from '@/lib/api-client'
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
 *
 * Hidden shops (#250): the backend returns EVERY listing with a `hidden`
 * flag and excludes hidden ones from the row's rollups. The panel hides
 * them unless "Show hidden" is on, and always says how many it is hiding,
 * so a product whose only shop is hidden never reads as "no shops".
 */

interface ListingRowProps {
  listing: Listing
  isBest: boolean
  now: number
  onSetHidden: (hidden: boolean) => void
  /** A visibility mutation is in flight — every row's toggle waits, so two clicks cannot interleave. */
  busy: boolean
  /** The last mutation for THIS row failed; text only, the button is the sibling. */
  error: boolean
}

function ListingRow({ listing, isBest, now, onSetHidden, busy, error }: ListingRowProps) {
  // Nullable on the wire for legacy rows only; one non-null value feeds the
  // label, the colour hash and the screen-reader text alike.
  const shopName = listing.shopName ?? 'Unknown shop'
  const href = safeExternalHref(listing.url)

  // Three price states (#157):
  //  1. converted present → the number the user compares, plus the shop's own
  //     price "at source" when the currencies differ (same wording as the row);
  //  2. only the original → the FX side failed (no snapshot yet / unknown
  //     currency): show it, but say the conversion is unavailable — a bare $
  //     on an ₪ page would read as intentional;
  //  3. neither → no CURRENT observation (never scraped, or gone cold past the
  //     carry-forward TTL). One neutral copy; the "last checked" column tells
  //     "never" from "9 days ago".
  const converted = formatPrice(listing.priceConverted, listing.priceConvertedCurrency)
  const original = formatPrice(listing.priceOriginal, listing.priceOriginalCurrency)
  const primary = converted ?? original
  const showOriginalAtSource =
    converted !== null && original !== null && listing.priceOriginalCurrency !== listing.priceConvertedCurrency
  const conversionUnavailable = converted === null && original !== null

  return (
    <div
      className={`flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-dashed border-line-strong px-4.5 py-2.5 ps-7 md:grid md:grid-cols-[minmax(150px,1fr)_120px_110px_130px_130px]${listing.hidden ? ' opacity-60' : ''}`}
    >
      <span className="shop-color inline-flex min-w-0 items-center gap-1.5 text-[13px] font-semibold" style={shopColorStyle(shopName)}>
        <span className="inline-block size-2 flex-none rounded-full bg-(--sc-dot)" aria-hidden="true" />
        <bdi className="truncate text-(--sc-text)">{shopName}</bdi>
        {/* Best comes from the row's rollup, which never counts a hidden listing — but a dashboard
            refetch parked behind "Prices updated" can still name one, so the panel checks too. */}
        {isBest && !listing.hidden && (
          <span className="ms-0.5 rounded-[5px] bg-good px-1.5 py-px text-[9.5px] font-extrabold uppercase tracking-wider text-white">
            Best
          </span>
        )}
        {listing.hidden && (
          <span className="ms-0.5 rounded-[5px] bg-ink-faint px-1.5 py-px text-[9.5px] font-extrabold uppercase tracking-wider text-white">
            Hidden
          </span>
        )}
      </span>
      <span className="font-num text-[14.5px] font-semibold tabular-nums md:text-right">
        {primary !== null ? (
          <>
            <bdi>{primary}</bdi>
            {showOriginalAtSource && (
              <span className="block text-[10px] font-normal text-ink-faint">
                <bdi>{original}</bdi> at source
              </span>
            )}
            {conversionUnavailable && (
              <span className="block text-[10px] font-normal text-ink-faint">conversion unavailable</span>
            )}
            {listing.conversionStale && (
              <span className="block text-[10px] font-semibold text-warn">Rate outdated</span>
            )}
          </>
        ) : (
          <span className="font-sans text-xs font-normal text-ink-faint">no current price</span>
        )}
      </span>
      <span className="md:justify-self-start">
        <ListingAvailabilityBadge status={listing.availability} />
      </span>
      <span className="text-xs text-ink-muted">{formatRelativeTime(listing.lastChecked, now)}</span>
      {/* One action cell so the grid keeps its five tracks: the hide toggle beside the Open link. */}
      <span className="inline-flex items-center gap-1.5 md:justify-self-end">
        <Button
          type="button"
          size="icon"
          variant="ghost"
          className="size-7 text-ink-muted hover:text-ink"
          aria-label={listing.hidden ? `Show ${shopName}` : `Hide ${shopName}`}
          title={listing.hidden ? `Show ${shopName}` : `Hide ${shopName}`}
          onClick={() => onSetHidden(!listing.hidden)}
          disabled={busy}
        >
          {listing.hidden ? <Eye className="size-4" aria-hidden="true" /> : <EyeOff className="size-4" aria-hidden="true" />}
        </Button>
        {href !== null ? (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 rounded-lg border border-line-strong bg-surface px-2 py-1 text-xs font-semibold text-iris-strong transition-colors hover:border-iris"
          >
            Open
            <ExternalLink className="size-3" aria-hidden="true" />
            <span className="sr-only"> {shopName} in a new tab</span>
          </a>
        ) : (
          // Unsafe/malformed/missing scraped URL — a non-interactive element,
          // not a dead link (no href="#" a11y trap).
          <span className="inline-flex items-center px-2 py-1 text-xs text-ink-faint" title="Link unavailable">
            Open
          </span>
        )}
      </span>
      {error && (
        <span role="alert" className="basis-full text-xs text-bad md:col-span-full">
          Couldn't {listing.hidden ? 'show' : 'hide'} {shopName}. Try again.
        </span>
      )}
    </div>
  )
}

interface ListingPanelProps {
  productId: number
  /** Expanded — the query is gated on this, so a collapsed row never fetches. */
  open: boolean
  /** The row's winning listing (#157) — Best is marked by identity, never by position. */
  bestTrackedItemId: number | null
  /** Dashboard-wide toggle (#250): render the hidden rows too, dimmed. */
  showHidden: boolean
  /** The panel's own way in: "N hidden · Show hidden" flips the dashboard toggle. */
  onShowHidden: () => void
}

export function ListingPanel({ productId, open, bestTrackedItemId, showHidden, onShowHidden }: ListingPanelProps) {
  const { data, status, refetch, isRefetching } = useQuery(listingsQueryOptions(productId, open))
  const now = useNow()
  const queryClient = useQueryClient()
  // Which row last failed; cleared by the next attempt on any row. One
  // mutation at a time (every toggle waits on `busy`), so a single slot.
  const [failedItemId, setFailedItemId] = useState<number | null>(null)
  const setHiddenMutation = useMutation({
    mutationFn: ({ trackedItemId, hidden }: { trackedItemId: number; hidden: boolean }) =>
      setListingHidden(productId, trackedItemId, hidden),
    onMutate: () => setFailedItemId(null),
    onError: (_error, { trackedItemId }) => setFailedItemId(trackedItemId),
    onSuccess: () => {
      // The panel for the flag; the dashboard for the row's best price, shop
      // count and facets, which only the backend recomputes.
      void queryClient.invalidateQueries({ queryKey: ['product-listings', productId] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

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

  const hiddenCount = data.filter((listing) => listing.hidden).length
  const rows = showHidden ? data : data.filter((listing) => !listing.hidden)

  // Rendered in WIRE ORDER — the backend sorts (not out of stock first, then
  // converted price ascending, unpriced last, ties by id); the client does no
  // money math. Best is the row's calculator winner, recognised by id.
  return (
    <div>
      {rows.map((listing) => (
        <ListingRow
          key={listing.trackedItemId}
          listing={listing}
          isBest={listing.trackedItemId === bestTrackedItemId}
          now={now}
          onSetHidden={(hidden) => setHiddenMutation.mutate({ trackedItemId: listing.trackedItemId, hidden })}
          busy={setHiddenMutation.isPending}
          error={failedItemId === listing.trackedItemId}
        />
      ))}
      {hiddenCount > 0 && !showHidden && (
        <div className="flex items-center gap-2 border-t border-dashed border-line-strong px-4.5 py-2 ps-7 text-xs text-ink-muted">
          <span>
            {hiddenCount} {hiddenCount === 1 ? 'shop' : 'shops'} hidden
          </span>
          <span aria-hidden="true">·</span>
          <button
            type="button"
            className="font-semibold text-iris-strong hover:underline"
            onClick={onShowHidden}
          >
            Show hidden
          </button>
        </div>
      )}
    </div>
  )
}
