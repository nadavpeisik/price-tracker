import { useEffect, useId, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, ExternalLink, EyeOff } from 'lucide-react'
import { listingsQueryOptions } from '@/lib/queries'
import { setListingHidden } from '@/lib/api-client'
import { formatPrice, formatRelativeTime } from '@/lib/format'
import { safeExternalHref } from '@/lib/safe-url'
import { shopColorStyle } from '@/lib/shop-colors'
import { useNow } from '@/hooks/use-now'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ListingAvailabilityBadge } from '@/components/dashboard/AvailabilityBadge'
import type { Listing } from '@/lib/types'

/**
 * Expanded per-shop listings (#144) — loaded LAZILY on row expand, in its
 * own query with its OWN row-level loading/error/retry (a detail-fetch
 * failure must never bubble to a page-level boundary or leave a blank
 * panel). This component is the only ticker consumer, so collapsed rows
 * never re-render on the minute tick.
 *
 * Hiding a shop (#250) is direct manipulation: the row LEAVES the list at
 * once and an undo line takes its slot. Restoring happens in the "Hidden
 * shops" section at the foot of this panel. There is deliberately no reveal
 * mode: a dashboard-wide "Show hidden" toggle makes Hide look broken, because
 * in that mode the row stays put.
 *
 * The backend returns EVERY listing with a `hidden` flag and excludes hidden
 * ones from the product row's rollups, so that section needs no extra request.
 */

/** How long the undo line survives before the hide becomes silent. */
export const UNDO_WINDOW_MS = 6000

/** Slack for a timer that fires before its deadline; see the sweep below. */
const TIMER_JITTER_MS = 25

/**
 * A row the caller just acted on, held in LOCAL state keyed by listing id.
 * It cannot be derived from the query: a successful hide triggers two
 * refetches (this query's own invalidation, plus the dashboard re-sync
 * effect), and the row comes back flagged `hidden`, which the render below
 * would otherwise drop — taking the undo line with it before its window is up.
 *
 * Every phase holds the row's slot in the list, so a row never jumps between
 * the list and the hidden section while a request settles.
 */
interface Pending {
  phase: 'hiding' | 'undoable' | 'restoring'
  /** The shop whose restore failed; rendered in JSX so `<bdi>` can isolate it. */
  error?: { shopName: string }
  /**
   * When this row's undo window closes. PER ENTRY: one shared deadline let a
   * second hide restart the first row's window.
   */
  expiresAt?: number
}

interface ListingRowProps {
  listing: Listing
  isBest: boolean
  now: number
  /** `keyboard` when the control was activated by Enter/Space rather than a pointer. */
  onHide: (keyboard: boolean) => void
  /** This row's last hide failed. Renders as text; there is no control in it. */
  failed: boolean
}

function ListingRow({ listing, isBest, now, onHide, failed }: ListingRowProps) {
  const shopName = listing.shopName ?? 'Unknown shop'
  return (
    <ListingRowShell
      listing={listing}
      now={now}
      muted={false}
      chip={isBest ? <span className={CHIP_CLASS + ' bg-good'}>Best</span> : null}
      action={
        <Button
          type="button"
          id={`hide-listing-${listing.trackedItemId}`}
          size="icon"
          variant="ghost"
          className="size-7 text-ink-muted hover:text-ink"
          aria-label={`Hide ${shopName}`}
          title={`Hide ${shopName}`}
          // `detail === 0` is a click synthesised from Enter/Space; a pointer click
          // reports 1 or more. Focus follows only the keyboard, because only a
          // keyboard user loses their place when this button unmounts.
          onClick={(event) => onHide(event.detail === 0)}
        >
          <EyeOff className="size-4" aria-hidden="true" />
        </Button>
      }
      footer={
        failed ? (
          <span role="alert" className="basis-full text-xs text-bad md:col-span-full">
            Couldn't hide <bdi>{shopName}</bdi>. Try again.
          </span>
        ) : null
      }
    />
  )
}

interface HiddenListingRowProps {
  listing: Listing
  now: number
  /** `keyboard` when the control was activated by Enter/Space rather than a pointer. */
  onRestore: (keyboard: boolean) => void
}

function HiddenListingRow({ listing, now, onRestore }: HiddenListingRowProps) {
  const shopName = listing.shopName ?? 'Unknown shop'
  return (
    <ListingRowShell
      listing={listing}
      now={now}
      // Muted TEXT, not a whole-row opacity: dimming the row would dim the
      // Restore control with it, which reads as a disabled/in-flight row.
      muted
      chip={null}
      action={
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="h-7 px-2 text-xs"
          aria-label={`Restore ${shopName}`}
          onClick={(event) => onRestore(event.detail === 0)}
        >
          Restore
        </Button>
      }
      footer={null}
    />
  )
}

const CHIP_CLASS =
  'ms-0.5 rounded-[5px] px-1.5 py-px text-[9.5px] font-extrabold uppercase tracking-wider text-white'

/**
 * The five-column grid every panel row shares, so visible and hidden rows line up.
 *
 * A plain string constant, never a template literal with the variant spliced in:
 * Tailwind scans SOURCE TEXT for class names, and an interpolation placed flush
 * against `md:grid-cols-[...]` makes the scanner read through the closing bracket,
 * so the class is never generated — `md:grid` then lands with no template and every
 * cell stacks into one column. Variant classes go through {@link cn} instead.
 */
const ROW_CLASS =
  'flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-dashed border-line-strong px-4.5 py-2.5 ps-7 md:grid md:grid-cols-[minmax(150px,1fr)_120px_110px_130px_130px]'

interface ListingRowShellProps {
  listing: Listing
  now: number
  muted: boolean
  chip: React.ReactNode
  action: React.ReactNode
  footer: React.ReactNode
}

function ListingRowShell({ listing, now, muted, chip, action, footer }: ListingRowShellProps) {
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
    <div className={ROW_CLASS}>
      <span
        className={cn(
          'shop-color inline-flex min-w-0 items-center gap-1.5 text-[13px] font-semibold',
          muted && 'opacity-70',
        )}
        style={shopColorStyle(shopName)}
      >
        <span className="inline-block size-2 flex-none rounded-full bg-(--sc-dot)" aria-hidden="true" />
        <bdi className="truncate text-(--sc-text)">{shopName}</bdi>
        {chip}
      </span>
      <span
        className={cn(
          'font-num text-[14.5px] font-semibold tabular-nums md:text-right',
          muted && 'text-ink-muted',
        )}
      >
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
      <span className={cn('md:justify-self-start', muted && 'opacity-70')}>
        <ListingAvailabilityBadge status={listing.availability} />
      </span>
      <span className="text-xs text-ink-muted">{formatRelativeTime(listing.lastChecked, now)}</span>
      {/* One action cell so the grid keeps its five tracks: the row's control beside the Open link. */}
      <span className="inline-flex items-center gap-1.5 md:justify-self-end">
        {action}
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
      {footer}
    </div>
  )
}

interface ListingPanelProps {
  productId: number
  /** Expanded — the query is gated on this, so a collapsed row never fetches. */
  open: boolean
  /** The row's winning listing (#157) — Best is marked by identity, never by position. */
  bestTrackedItemId: number | null
  /** Reported after a successful hide/restore so the row reorder it causes lands at once. */
  onListingsChanged: () => void
}

export function ListingPanel({ productId, open, bestTrackedItemId, onListingsChanged }: ListingPanelProps) {
  const { data, status, refetch, isRefetching } = useQuery(listingsQueryOptions(productId, open))
  const now = useNow()
  const queryClient = useQueryClient()

  const hiddenSectionId = useId()
  const [pending, setPending] = useState<ReadonlyMap<number, Pending>>(new Map())
  // A SET, not one id: mutations can overlap, and a second failure must not
  // erase the first one's alert.
  const [failedIds, setFailedIds] = useState<ReadonlySet<number>>(new Set())
  const [hiddenOpen, setHiddenOpen] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  /**
   * The row whose Undo holds focus, or null. Its window never expires while
   * focused, HOWEVER focus arrived: exempting programmatic focus let the timer
   * unmount a focused button and drop focus to the body. Keyed by id, not a
   * panel-wide boolean, or one focused row would hold every other row's window
   * open and expire them all together when focus leaves.
   */
  const [focusedUndoId, setFocusedUndoId] = useState<number | null>(null)
  // A ref, not state: a one-shot instruction to the effect below, and clearing
  // it must not itself cause a render. Holds the DOM id to focus after render.
  const focusAfterRender = useRef<string | null>(null)

  const shopNameOf = (trackedItemId: number) =>
    data?.find((listing) => listing.trackedItemId === trackedItemId)?.shopName ?? 'Unknown shop'

  const setPendingFor = (trackedItemId: number, value: Pending | null) =>
    setPending((current) => {
      const next = new Map(current)
      if (value === null) next.delete(trackedItemId)
      else next.set(trackedItemId, value)
      return next
    })

  const visibility = useMutation({
    mutationFn: ({ trackedItemId, hidden }: { trackedItemId: number; hidden: boolean; moveFocus?: boolean }) =>
      setListingHidden(productId, trackedItemId, hidden),
    onMutate: ({ trackedItemId, hidden }) => {
      // Cleared first: two identical outcomes in a row leave the live region's
      // text unchanged, and an unchanged region announces nothing.
      setAnnouncement('')
      setFailedIds((current) => {
        if (!current.has(trackedItemId)) return current
        const next = new Set(current)
        next.delete(trackedItemId)
        return next
      })
      // Both directions take a phase, so the row holds its slot until the
      // request settles instead of jumping to the hidden section and back.
      setPendingFor(trackedItemId, { phase: hidden ? 'hiding' : 'restoring' })
    },
    onError: (_error, { trackedItemId, hidden, moveFocus }) => {
      const shopName = shopNameOf(trackedItemId)
      if (hidden) {
        // The row returns to where it was, carrying the error — never a silent no-op.
        setPendingFor(trackedItemId, null)
        setFailedIds((current) => new Set(current).add(trackedItemId))
        if (moveFocus) focusAfterRender.current = `hide-listing-${trackedItemId}`
      } else {
        // A failed restore stays in its slot as an undo line that says so, and
        // the button is still there to retry. Clearing would drop it into the
        // hidden section, whose alert may sit inside a collapsed drawer.
        setPendingFor(trackedItemId, { phase: 'undoable', error: { shopName } })
        // The control the caller activated unmounted for the `restoring` phase;
        // send focus to the Retry that replaced it.
        if (moveFocus) focusAfterRender.current = `undo-listing-${trackedItemId}`
      }
      // No live-region announcement on either failure: the row that comes back
      // carries a `role="alert"`, and saying it here too reads the same sentence
      // twice on some assistive tech.
    },
    onSuccess: (_result, { trackedItemId, hidden, moveFocus }) => {
      const shopName = shopNameOf(trackedItemId)
      setAnnouncement(hidden ? `${shopName} hidden` : `${shopName} restored`)
      // Write the new flag into the cache before the refetch lands, so the row
      // and its phase never disagree: without it a restore clears its phase
      // while the cache still says hidden, and the row blinks out of the list.
      queryClient.setQueryData<Listing[]>(['product-listings', productId], (current) =>
        current?.map((listing) => (listing.trackedItemId === trackedItemId ? { ...listing, hidden } : listing)),
      )
      if (hidden) {
        setPendingFor(trackedItemId, { phase: 'undoable', expiresAt: Date.now() + UNDO_WINDOW_MS })
        if (moveFocus) focusAfterRender.current = `undo-listing-${trackedItemId}`
      } else {
        setPendingFor(trackedItemId, null)
        // The row returns to the list, so its Hide control is where the caller
        // left off; without this, activating Undo drops focus to the body.
        if (moveFocus) focusAfterRender.current = `hide-listing-${trackedItemId}`
      }
      // The panel for the flag; the dashboard for the row's best price, shop
      // count and facets, which only the backend recomputes. Hiding the best
      // shop can reorder the dashboard, and `onListingsChanged` is what marks
      // that reorder as asked-for rather than a background one.
      void queryClient.invalidateQueries({ queryKey: ['product-listings', productId] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      onListingsChanged()
    },
  })

  // Each undoable row owns its deadline; this schedules only the NEAREST one and
  // re-runs after each sweep, so a second hide cannot extend the first row's
  // window. Cleared on unmount, so collapsing the product row ends every window.
  useEffect(() => {
    // A row mid-request is not eligible, and neither is one showing an error:
    // dismissing that would hide the failure and return the row to the drawer.
    const deadlines = [...pending]
      .filter(
        ([id, value]) =>
          value.phase === 'undoable' &&
          value.error === undefined &&
          value.expiresAt !== undefined &&
          id !== focusedUndoId,
      )
      .map(([, value]) => value.expiresAt as number)
    if (deadlines.length === 0) return
    const timer = window.setTimeout(
      () =>
        setPending((current) => {
          const dueBy = Date.now()
          const next = new Map(current)
          for (const [id, value] of current) {
            if (
              value.phase === 'undoable' &&
              value.error === undefined &&
              id !== focusedUndoId &&
              // Explicit rather than `?? 0`, which would read a missing deadline
              // as long expired: only an entry that HAS one can run out.
              value.expiresAt !== undefined &&
              // A timer may fire a hair EARLY (jitter, clock rounding). Without
              // this tolerance the sweep would delete nothing, return the same
              // reference, skip the re-render, and never schedule a successor —
              // stranding the line on screen for good.
              value.expiresAt <= dueBy + TIMER_JITTER_MS
            ) {
              next.delete(id)
            }
          }
          return next.size === current.size ? current : next
        }),
      Math.max(0, Math.min(...deadlines) - Date.now()),
    )
    return () => window.clearTimeout(timer)
  }, [pending, focusedUndoId])

  // The control the caller activated has unmounted, so focus would fall to the
  // document body. Move it to whichever control took its place.
  useEffect(() => {
    const elementId = focusAfterRender.current
    if (elementId === null) return
    focusAfterRender.current = null
    document.getElementById(elementId)?.focus()
  }, [pending])

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
    // Zero tracked items is a real, valid state (#144), and a different one
    // from "you hid them all" below.
    return <div className="px-4.5 py-3 ps-7 text-sm text-ink-muted">No shops tracked for this product yet.</div>
  }

  // Rendered in WIRE ORDER — the backend sorts (not out of stock first, then
  // converted price ascending, unpriced last, ties by id); the client does no
  // money math. A row the caller just hid keeps its slot as an undo line until
  // the window closes, which is also what stops it appearing twice: the hidden
  // section below takes only rows with no phase of their own.
  const slots = data.filter((listing) => !listing.hidden || pending.has(listing.trackedItemId))
  const hidden = data.filter((listing) => listing.hidden && !pending.has(listing.trackedItemId))
  const allHidden = slots.length === 0 && hidden.length > 0

  return (
    <div>
      {slots.map((listing) => {
        const slot = pending.get(listing.trackedItemId)
        const shopName = listing.shopName ?? 'Unknown shop'
        if (slot === undefined) {
          return (
            <ListingRow
              key={listing.trackedItemId}
              listing={listing}
              isBest={listing.trackedItemId === bestTrackedItemId}
              now={now}
              onHide={(keyboard) =>
                visibility.mutate({ trackedItemId: listing.trackedItemId, hidden: true, moveFocus: keyboard })
              }
              failed={failedIds.has(listing.trackedItemId)}
            />
          )
        }
        return (
          <div
            key={listing.trackedItemId}
            className="flex items-center gap-2 border-t border-dashed border-line-strong px-4.5 py-2.5 ps-7 text-xs text-ink-muted"
          >
            {slot.phase === 'hiding' && (
              <span>
                Hiding <bdi>{shopName}</bdi>…
              </span>
            )}
            {slot.phase === 'restoring' && (
              <span>
                Restoring <bdi>{shopName}</bdi>…
              </span>
            )}
            {slot.phase === 'undoable' && (
              <>
                {slot.error === undefined ? (
                  <span>
                    <bdi>{shopName}</bdi> hidden
                  </span>
                ) : (
                  <span role="alert" className="text-bad">
                    Couldn't restore <bdi>{slot.error.shopName}</bdi>.
                  </span>
                )}
                <span aria-hidden="true">·</span>
                <button
                  type="button"
                  id={`undo-listing-${listing.trackedItemId}`}
                  className="font-semibold text-iris-strong hover:underline"
                  // After a failed restore this retries the restore; calling it
                  // "Undo hiding" would name the wrong operation.
                  aria-label={
                    slot.error === undefined ? `Undo hiding ${shopName}` : `Retry restoring ${shopName}`
                  }
                  onFocus={() => setFocusedUndoId(listing.trackedItemId)}
                  onBlur={() =>
                    setFocusedUndoId((current) => (current === listing.trackedItemId ? null : current))
                  }
                  onClick={(event) => {
                    setFocusedUndoId(null)
                    visibility.mutate({
                      trackedItemId: listing.trackedItemId,
                      hidden: false,
                      moveFocus: event.detail === 0,
                    })
                  }}
                >
                  {slot.error === undefined ? 'Undo' : 'Retry'}
                </button>
              </>
            )}
          </div>
        )
      })}

      {hidden.length > 0 &&
        (allHidden ? (
          // Nothing visible is left, so there is nothing to expand INTO: show the
          // hidden rows directly rather than leaving an empty panel behind a toggle.
          <>
            <p className="border-t border-dashed border-line-strong px-4.5 py-2 ps-7 text-xs text-ink-muted">
              All shops are hidden · excluded from price comparisons
            </p>
            {hidden.map((listing) => (
              <HiddenListingRow
                key={listing.trackedItemId}
                listing={listing}
                now={now}
                onRestore={(keyboard) => {
                  // Restoring one flips this branch off, so open the section the
                  // remaining rows are about to live in — they must not vanish
                  // mid-interaction behind a collapsed toggle.
                  setHiddenOpen(true)
                  visibility.mutate({
                    trackedItemId: listing.trackedItemId,
                    hidden: false,
                    moveFocus: keyboard,
                  })
                }}
              />
            ))}
          </>
        ) : (
          <>
            <button
              type="button"
              className="flex w-full items-center gap-1.5 border-t border-dashed border-line-strong px-4.5 py-2 ps-7 text-start text-xs text-ink-muted hover:text-ink"
              aria-expanded={hiddenOpen}
              aria-controls={hiddenSectionId}
              onClick={() => setHiddenOpen((cur) => !cur)}
            >
              {hiddenOpen ? (
                <ChevronDown className="size-3.5" aria-hidden="true" />
              ) : (
                <ChevronRight className="size-3.5" aria-hidden="true" />
              )}
              <span>
                Hidden {hidden.length === 1 ? 'shop' : 'shops'} ({hidden.length})
              </span>
              <span aria-hidden="true">·</span>
              {/* Said out loud because hiding a shop moves the product's best price, and a
                  number that changes for no visible reason reads as a bug. */}
              <span className="font-normal text-ink-faint">excluded from price comparisons</span>
            </button>
            <div id={hiddenSectionId}>
              {hiddenOpen &&
                hidden.map((listing) => (
                  <HiddenListingRow
                    key={listing.trackedItemId}
                    listing={listing}
                    now={now}
                    onRestore={(keyboard) =>
                      visibility.mutate({
                        trackedItemId: listing.trackedItemId,
                        hidden: false,
                        moveFocus: keyboard,
                      })
                    }
                  />
                ))}
            </div>
          </>
        ))}

      {/* Polite, because a visual swap announces nothing to a screen reader. */}
      <span role="status" aria-live="polite" className="sr-only">
        {announcement}
      </span>
    </div>
  )
}
