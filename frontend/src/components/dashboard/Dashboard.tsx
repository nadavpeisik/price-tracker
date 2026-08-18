import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Moon, RefreshCw, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { dashboardQueryOptions, PAGE_SIZE } from '@/lib/queries'
import { safeStorage } from '@/lib/safe-storage'
import { collectCelebrations, createCelebrationState } from '@/lib/celebration'
import { canonicalizeShops, sameShops } from '@/lib/shop-identity'
import { useTheme } from '@/hooks/use-theme'
import { useDashboardUrlState } from '@/hooks/use-dashboard-url-state'
import { SummaryTiles } from '@/components/dashboard/SummaryTiles'
import { Toolbar } from '@/components/dashboard/Toolbar'
import { ProductRow } from '@/components/dashboard/ProductRow'
import { ErrorState, NoMatchesState, SkeletonRows, ZeroTrackedState } from '@/components/dashboard/ListStates'
import { RocketIntro } from '@/components/dashboard/RocketIntro'
import type { DashboardQuery, DashboardResponse } from '@/lib/types'

/** Persisted first-run flag for the rocket intro (via the safe-storage helper). */
export const INTRO_SEEN_KEY = 'ph.intro-seen'

/** How long a row stays in "celebrating" mode after a live drop. */
const CELEBRATION_RESET_MS = 1600

interface Committed {
  key: string
  data: DashboardResponse
}

function sameIdOrder(a: DashboardResponse, b: DashboardResponse): boolean {
  if (a.items.length !== b.items.length) return false
  return a.items.every((item, i) => item.id === b.items[i].id)
}

export function Dashboard() {
  const { theme, toggle } = useTheme()
  const { state, update } = useDashboardUrlState()

  const query: DashboardQuery = useMemo(
    () => ({
      search: state.search || undefined,
      shops: state.shops.length > 0 ? state.shops : undefined,
      sort: state.sort,
      page: state.page,
      size: PAGE_SIZE,
    }),
    [state],
  )
  const queryKey = useMemo(() => JSON.stringify(query), [query])
  const result = useQuery(dashboardQueryOptions(query))

  /*
   * Committed-order model (#144): what the list RENDERS. A background
   * refetch for the SAME params must not silently reorder rows under the
   * reader — if the id order changed, hold it as `pending` behind a
   * "Prices updated" affordance. A user-initiated change (new params) or an
   * in-place update (same order) commits immediately.
   *
   * Implemented as the guarded adjust-state-during-render pattern (not an
   * effect): TanStack's structural sharing keeps `result.data` reference-
   * stable when nothing changed, so the guards below settle immediately.
   */
  const [committed, setCommitted] = useState<Committed | null>(null)
  const [pending, setPending] = useState<DashboardResponse | null>(null)
  const [celebrating, setCelebrating] = useState<ReadonlySet<number>>(new Set())
  const celebrationRef = useRef(createCelebrationState())

  const incoming = result.isPlaceholderData ? undefined : result.data
  if (incoming !== undefined) {
    if (committed === null || committed.key !== queryKey) {
      // Fresh view (first load or user-initiated param change) → commit now.
      setCommitted({ key: queryKey, data: incoming })
      if (pending !== null) setPending(null)
    } else if (committed.data !== incoming) {
      if (sameIdOrder(committed.data, incoming)) {
        // In-place update (prices moved, order intact) → commit silently.
        setCommitted({ key: queryKey, data: incoming })
        if (pending !== null) setPending(null)
      } else if (pending !== incoming) {
        // Background reorder for the same view — park it, don't yank rows.
        setPending(incoming)
      }
    }
  }

  // Live price-drop celebrations, driven by every commit. The bookkeeping
  // mutates a session-level snapshot, so it lives in an effect (not render);
  // state updates happen inside timer callbacks.
  useEffect(() => {
    if (committed === null) return
    const ids = collectCelebrations(celebrationRef.current, committed.data.items)
    if (ids.length === 0) return
    const showTimer = window.setTimeout(() => setCelebrating(new Set(ids)), 0)
    const clearTimer = window.setTimeout(() => setCelebrating(new Set()), CELEBRATION_RESET_MS)
    return () => {
      window.clearTimeout(showTimer)
      window.clearTimeout(clearTimer)
    }
  }, [committed])

  const data = committed?.data
  const filterActive = state.search !== '' || state.shops.length > 0

  // Canonicalize bookmarked ?shop= values against the facets — but only AFTER
  // facets have loaded (never discard a valid shop during loading). The
  // backend folds shop identities case-insensitively and labels each facet
  // with its majority spelling, so `?shop=ksp` must become the "KSP" chip
  // rather than being pruned as unknown (#157). A pure spelling rewrite keeps
  // the bookmarked page (`update` resets it whenever `shops` changes unless
  // the patch paginates); dropping a genuinely unknown shop lets that reset
  // happen, because the result set really did change.
  useEffect(() => {
    if (data === undefined) return
    const { shops, droppedUnknown } = canonicalizeShops(state.shops, data.facets.shops)
    if (sameShops(shops, state.shops)) return
    update(droppedUnknown ? { shops } : { shops, page: state.page })
  }, [data, state.shops, state.page, update])

  // Re-sync open listing panels on every successful dashboard fetch (#157):
  // the rows' `bestTrackedItemId` and the panel's prices come from two
  // requests, and a panel left expanded across a background refresh would
  // otherwise keep showing stale prices under a chip the row may have moved.
  // Keyed on `dataUpdatedAt`, NOT on `committed`: structural sharing keeps
  // `result.data` reference-stable when a refetch is deeply equal, so a
  // refresh that only moved a NON-winning listing's price leaves every row
  // identical — `committed` never changes and the open panel would stay stale
  // indefinitely. The timestamp advances on each successful fetch regardless.
  // Only MOUNTED (expanded) listing queries actually refetch on invalidation.
  // In an effect, never in the render-phase commit above — invalidation
  // notifies other components' observers, which is a side effect.
  const queryClient = useQueryClient()
  const dashboardFetchedAt = result.isPlaceholderData ? 0 : result.dataUpdatedAt
  useEffect(() => {
    if (dashboardFetchedAt === 0) return
    void queryClient.invalidateQueries({ queryKey: ['product-listings'] })
  }, [dashboardFetchedAt, queryClient])

  // Bookmarked overflow page → clamp to totalPages (not 1 — respect the
  // link) once the real page count is known. Skeleton covers the gap.
  const totalPages = data?.page.totalPages ?? 0
  const clamping = data !== undefined && totalPages >= 1 && state.page > totalPages
  useEffect(() => {
    if (clamping) update({ page: totalPages })
  }, [clamping, totalPages, update])

  // First-run rocket intro: DERIVED, not set in an effect — eligible only
  // when the query actually resolved empty (success + zero tracked, no
  // filters) and the persisted first-run flag is unset. Never over a
  // skeleton or an error, never for returning users.
  const [introDismissed, setIntroDismissed] = useState(false)
  const showIntro =
    !introDismissed &&
    result.status === 'success' &&
    !result.isPlaceholderData &&
    data !== undefined &&
    data.page.totalElements === 0 &&
    !filterActive &&
    safeStorage.get(INTRO_SEEN_KEY) === null
  const dismissIntro = () => {
    safeStorage.set(INTRO_SEEN_KEY, 'true')
    setIntroDismissed(true)
  }

  const [expandedId, setExpandedId] = useState<number | null>(null)

  // Whether the committed data still describes the CURRENT query. When the
  // user changes search/filter/sort/page, `queryKey` changes immediately but
  // `committed` lags until the new response lands — so a NEW-key request that
  // FAILS must show the error/retry (not the stale rows from the old key).
  // A same-key background refetch that fails keeps the committed data on
  // screen (transient failure shouldn't yank the page out from under you).
  const committedIsCurrent = committed !== null && committed.key === queryKey
  const showPageError = result.status === 'error' && !committedIsCurrent

  const showSkeleton = !showPageError && (data === undefined || clamping)
  const isEmpty = !showPageError && !showSkeleton && data !== undefined && data.items.length === 0

  // List body as flat, ordered branches (avoids a deep nested ternary in JSX).
  let listBody: React.ReactNode
  if (showPageError) {
    listBody = <ErrorState onRetry={() => void result.refetch()} />
  } else if (showSkeleton) {
    listBody = <SkeletonRows />
  } else if (isEmpty) {
    listBody = filterActive ? (
      <NoMatchesState onClear={() => update({ search: '', shops: [], page: 1 })} />
    ) : (
      <ZeroTrackedState />
    )
  } else {
    listBody = data!.items.map((product, index) => (
      <ProductRow
        key={product.id}
        product={product}
        index={index}
        expanded={expandedId === product.id}
        onToggle={() => setExpandedId((cur) => (cur === product.id ? null : product.id))}
        celebrate={celebrating.has(product.id)}
      />
    ))
  }

  return (
    <div className="mx-auto max-w-[1080px] px-5 pb-24 pt-6">
      <header className="mb-6 flex items-center justify-between gap-4">
        <div className="flex items-center gap-2.5">
          <span
            aria-hidden="true"
            className="relative size-[30px] rounded-[9px] bg-[linear-gradient(145deg,#D63C93,var(--iris)_55%,#2F6FE0)] shadow-[0_5px_14px_-4px_color-mix(in_srgb,var(--iris)_60%,transparent)] after:absolute after:inset-[9px] after:rounded-full after:border-[2.5px] after:border-surface after:content-['']"
          />
          <span className="font-display text-xl font-bold tracking-tight">PriceHunt</span>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="icon"
            onClick={toggle}
            aria-label="Toggle light and dark theme"
            className="size-9 rounded-[10px] border-line-strong bg-surface text-ink-muted"
          >
            {theme === 'dark' ? <Moon className="size-4" /> : <Sun className="size-4" />}
          </Button>
          {/* Add-product flow is out of scope this issue — visual stub. */}
          <Button className="rounded-[10px] font-semibold">+ Track a product</Button>
        </div>
      </header>

      {data !== undefined && (
        <SummaryTiles
          global={data.globalSummary}
          forCurrentQuery={data.summaryForCurrentQuery}
          // Only annotate "N in this filter" when the committed summary
          // actually describes the active query. During a new-key load or a
          // failed new-key query, `committed` still holds the previous
          // query's counts — the global tiles stay valid (filter-independent)
          // but the filtered annotation would be stale, so suppress it.
          filterActive={filterActive && committedIsCurrent}
        />
      )}

      <Toolbar state={state} update={update} shops={data?.facets.shops} />

      <div className="overflow-hidden rounded-2xl border border-line bg-surface shadow-card">
        <div className="hidden border-b border-line bg-surface-2 px-4.5 py-2.5 text-[10.5px] font-bold uppercase tracking-wider text-ink-faint md:grid md:grid-cols-[minmax(190px,1fr)_76px_132px_92px_130px_32px] md:gap-3">
          <span>Product</span>
          <span>Trend</span>
          <span className="text-right">Best price</span>
          <span className="text-right">7-day</span>
          <span>Availability</span>
          <span />
        </div>

        {listBody}
      </div>

      {!showPageError && !showSkeleton && data !== undefined && totalPages > 1 && (
        <nav className="mt-4 flex items-center justify-center gap-3" aria-label="Pagination">
          <Button
            variant="outline"
            size="sm"
            disabled={state.page <= 1}
            onClick={() => update({ page: state.page - 1 })}
          >
            <ChevronLeft className="size-4" aria-hidden="true" /> Prev
          </Button>
          <span className="text-sm text-ink-muted tabular-nums">
            Page {data.page.number} of {totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={state.page >= totalPages}
            onClick={() => update({ page: state.page + 1 })}
          >
            Next <ChevronRight className="size-4" aria-hidden="true" />
          </Button>
        </nav>
      )}

      {pending !== null && (
        <div className="fixed inset-x-0 bottom-5 z-40 flex justify-center">
          <Button
            onClick={() => {
              // Explicit user action — replace order/page data now.
              setCommitted({ key: queryKey, data: pending })
              collectCelebrations(celebrationRef.current, pending.items)
              setPending(null)
            }}
            className="rounded-full shadow-card-hover"
          >
            <RefreshCw className="size-4" aria-hidden="true" /> Prices updated — refresh
          </Button>
        </div>
      )}

      {showIntro && <RocketIntro onDone={dismissIntro} />}
    </div>
  )
}
