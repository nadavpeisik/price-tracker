import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { TooltipProvider } from '@/components/ui/tooltip'
import { dispatchMatchMediaChange, resetMatchMedia, setMatchMediaMatches } from '@/test/setup'
import { Dashboard } from '@/components/dashboard/Dashboard'
import { UNDO_WINDOW_MS } from '@/components/dashboard/ListingPanel'
import type { DashboardResponse, Listing, TrackedProduct } from '@/lib/types'

/* ── api-client mock (the Dashboard's only IO boundary) ─────────────── */

const { fetchDashboardMock, fetchListingsMock, setListingHiddenMock } = vi.hoisted(() => ({
  fetchDashboardMock: vi.fn(),
  fetchListingsMock: vi.fn(),
  setListingHiddenMock: vi.fn(),
}))

vi.mock('@/lib/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api-client')>()),
  fetchDashboard: fetchDashboardMock,
  fetchListings: fetchListingsMock,
  setListingHidden: setListingHiddenMock,
}))

/* ── fixtures ────────────────────────────────────────────────────────── */

const HOUR = 3_600_000

function product(overrides: Partial<TrackedProduct> & { id: number; name: string }): TrackedProduct {
  return {
    imageUrl: null,
    category: null,
    bestPriceConverted: '100.00',
    bestPriceConvertedCurrency: 'ILS',
    bestPriceOriginal: '100.00',
    bestPriceOriginalCurrency: 'ILS',
    bestPriceShop: 'KSP',
    bestTrackedItemId: 11,
    conversionStale: false,
    conversionAsOf: null,
    mixedCurrencies: false,
    availability: { status: 'AVAILABLE', availableCount: 2, total: 2 },
    delta7d: -6,
    sparkline: [
      { t: new Date(Date.now() - 8 * 24 * HOUR).toISOString(), price: '110.00' },
      { t: new Date(Date.now() - 1 * 24 * HOUR).toISOString(), price: '100.00' },
    ],
    ...overrides,
  }
}

const PRODUCTS: TrackedProduct[] = [
  product({ id: 1, name: 'Sony WH-1000XM5', delta7d: -6.04 }),
  product({
    id: 2,
    name: 'Apple AirPods Pro 2',
    delta7d: null, // under 7d of history → New
    availability: { status: 'MIXED', availableCount: 1, total: 2 },
  }),
  product({
    id: 3,
    name: 'Framework Laptop 16',
    bestPriceConverted: null,
    bestPriceConvertedCurrency: null,
    bestPriceOriginal: null,
    bestPriceOriginalCurrency: null,
    bestPriceShop: null,
    bestTrackedItemId: null,
    delta7d: null,
    sparkline: [],
    availability: { status: 'UNAVAILABLE', availableCount: 0, total: 1 },
  }),
  product({
    id: 4,
    name: 'מקלדת Keychron K8 Pro',
    mixedCurrencies: true,
    conversionStale: true,
    delta7d: 2.4,
  }),
]

function response(items: TrackedProduct[], overrides?: Partial<DashboardResponse>): DashboardResponse {
  return {
    items,
    page: { number: 1, size: 20, totalElements: items.length, totalPages: items.length > 0 ? 1 : 0 },
    facets: { shops: ['Bug', 'Ivory', 'KSP'] },
    globalSummary: { totalTracked: 4, drops7d: 1, biggestDrop: { productId: 1, productName: 'Sony WH-1000XM5', deltaPct: -6.04 } },
    summaryForCurrentQuery: { totalTracked: items.length, drops7d: 1, biggestDrop: null },
    ...overrides,
  }
}

// Wire order is display order (#157) — the panel must NOT reorder. The Best
// listing (11, per the product fixture's bestTrackedItemId) is deliberately
// handed SECOND so a positional "first is best" heuristic would fail.
const LISTINGS: Listing[] = [
  {
    trackedItemId: 12,
    shopName: 'Bug',
    url: 'javascript:alert(1)', // scheme-guard fixture
    priceOriginal: '120.00',
    priceOriginalCurrency: 'ILS',
    priceConverted: '120.00',
    priceConvertedCurrency: 'ILS',
    conversionStale: false,
    hidden: false,
    availability: 'UNKNOWN',
    lastChecked: null,
  },
  {
    trackedItemId: 11,
    shopName: 'KSP',
    url: 'https://ksp.co.il/web/item/1',
    priceOriginal: '100.00',
    priceOriginalCurrency: 'ILS',
    priceConverted: '100.00',
    priceConvertedCurrency: 'ILS',
    conversionStale: false,
    hidden: false,
    availability: 'AVAILABLE',
    lastChecked: new Date(Date.now() - 2 * HOUR).toISOString(),
    // 2 hours ago → "checked 2h ago"
  },
]

/* ── harness ─────────────────────────────────────────────────────────── */

function renderDashboard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <Dashboard />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return { ...utils, client }
}

beforeEach(() => {
  vi.clearAllMocks()
  resetMatchMedia()
  localStorage.clear()
  window.history.replaceState(null, '', '/')
  fetchDashboardMock.mockResolvedValue(response(PRODUCTS))
  fetchListingsMock.mockResolvedValue(LISTINGS)
})

/* ── tests ───────────────────────────────────────────────────────────── */

describe('Dashboard', () => {
  it('renders the grouped product list with sublines and rollup badges', async () => {
    renderDashboard()
    expect(await screen.findByRole('button', { name: 'Sony WH-1000XM5' })).toBeInTheDocument()
    expect(screen.getAllByText(/2 shops · best at/)[0]).toBeInTheDocument()
    expect(screen.getByText('1 of 2 in stock')).toBeInTheDocument()
    expect(screen.getByText('Out of stock')).toBeInTheDocument()
  })

  it('renders supplied delta7d: value pills, strict-null New, and rise direction', async () => {
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    expect(screen.getByLabelText('7-day change: down 6.0%')).toBeInTheDocument()
    expect(screen.getByLabelText('7-day change: up 2.4%')).toBeInTheDocument()
    // Two null-delta products → two New tags (never for the flat/rise ones).
    expect(screen.getAllByText('New')).toHaveLength(2)
  })

  it('renders null best price as a neutral placeholder, plus stale/mixed flags', async () => {
    renderDashboard()
    await screen.findByText('Framework Laptop 16')
    expect(screen.getByText('No price yet')).toBeInTheDocument()
    expect(screen.getByText(/Rate outdated/)).toBeInTheDocument()
    expect(screen.getByText('mixed currencies')).toBeInTheDocument()
  })

  it('commits the debounced search to the URL and refetches', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    fetchDashboardMock.mockResolvedValue(response([PRODUCTS[0]]))
    await user.type(screen.getByRole('searchbox', { name: 'Search products' }), 'sony')
    await waitFor(() =>
      expect(fetchDashboardMock).toHaveBeenCalledWith(expect.objectContaining({ search: 'sony', page: 1 })),
    )
    expect(window.location.search).toBe('?q=sony')
  })

  it('filters by shop chips (pressed state announced) and syncs the URL', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    const chips = screen.getByRole('toolbar', { name: 'Filter by shop' })
    const ksp = within(chips).getByRole('button', { name: /KSP/ })
    expect(ksp).toHaveAttribute('aria-pressed', 'false')
    await user.click(ksp)
    expect(ksp).toHaveAttribute('aria-pressed', 'true')
    expect(window.location.search).toBe('?shop=KSP')
    await waitFor(() =>
      expect(fetchDashboardMock).toHaveBeenCalledWith(expect.objectContaining({ shops: ['KSP'] })),
    )
  })

  it('expands and collapses a row from the keyboard with correct ARIA wiring', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    const toggle = screen.getByRole('button', { name: 'Sony WH-1000XM5', expanded: false })
    toggle.focus()
    await user.keyboard('{Enter}')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    expect(await within(region).findByText('KSP')).toBeInTheDocument()
    expect(within(region).getByText('checked 2h ago')).toBeInTheDocument()
    expect(within(region).getByText('never checked')).toBeInTheDocument()
    expect(within(region).getByText('Best')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('never binds an unsafe scraped URL to a link (javascript: yields no live link)', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    const links = await within(region).findAllByRole('link')
    // Only the safe https listing gets a link, with the right rel/target.
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href', 'https://ksp.co.il/web/item/1')
    expect(links[0]).toHaveAttribute('rel', 'noopener noreferrer')
    expect(links[0]).toHaveAttribute('target', '_blank')
  })

  it('shows a row-level retry when the lazy listings fetch fails, without a page error', async () => {
    fetchListingsMock.mockRejectedValueOnce(new Error('boom'))
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    expect(await within(region).findByText(/Couldn't load listings/)).toBeInTheDocument()
    // The page itself is intact — recovery is row-scoped.
    expect(screen.getByText('Apple AirPods Pro 2')).toBeInTheDocument()
    await user.click(within(region).getByRole('button', { name: 'Retry' }))
    expect(await within(region).findByText('checked 2h ago')).toBeInTheDocument()
  })

  it('shows an accessible page-level error with recovery when the dashboard query fails', async () => {
    fetchDashboardMock.mockRejectedValueOnce(new Error('down'))
    const user = userEvent.setup()
    renderDashboard()
    expect(await screen.findByRole('alert')).toHaveTextContent(/Couldn't load/)
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('button', { name: 'Sony WH-1000XM5' })).toBeInTheDocument()
  })

  it('shows the error state (not stale rows) when a user-initiated filter change fails', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    // The next fetch — triggered by the shop filter (a NEW query key) — fails.
    fetchDashboardMock.mockRejectedValueOnce(new Error('down'))
    await user.click(
      within(screen.getByRole('toolbar', { name: 'Filter by shop' })).getByRole('button', {
        name: /KSP/,
      }),
    )
    // The failed new-key query must surface the error, NOT keep the old
    // unfiltered rows on screen under the new "?shop=KSP" URL.
    expect(await screen.findByRole('alert')).toHaveTextContent(/Couldn't load/)
    expect(screen.queryByRole('button', { name: 'Sony WH-1000XM5' })).not.toBeInTheDocument()
    // ...and the stale "N in this filter" annotation from the old query must
    // not linger over the failed one (global tiles stay, annotation drops).
    expect(screen.queryByText(/in this filter/)).not.toBeInTheDocument()
  })

  const EMPTY_RESPONSE = () =>
    response([], {
      globalSummary: { totalTracked: 0, drops7d: 0, biggestDrop: null },
      summaryForCurrentQuery: { totalTracked: 0, drops7d: 0, biggestDrop: null },
    })

  it('shows the zero-tracked rocket empty state when nothing is tracked', async () => {
    fetchDashboardMock.mockResolvedValue(EMPTY_RESPONSE())
    renderDashboard()
    expect(await screen.findByText('Nothing tracked yet')).toBeInTheDocument()
  })

  it('shows the no-matches empty state (with clear-filters) when a filter matches nothing', async () => {
    fetchDashboardMock.mockResolvedValue(EMPTY_RESPONSE())
    window.history.replaceState(null, '', '/?q=zzz')
    const user = userEvent.setup()
    renderDashboard()
    expect(await screen.findByText(/No products match/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(window.location.search).toBe('')
  })

  it('reads a bookmarked view from the URL on load', async () => {
    window.history.replaceState(null, '', '/?q=sony&shop=KSP&sort=name&page=1')
    renderDashboard()
    await waitFor(() =>
      expect(fetchDashboardMock).toHaveBeenCalledWith(
        expect.objectContaining({ search: 'sony', shops: ['KSP'], sort: 'name' }),
      ),
    )
  })

  it('prunes unknown bookmarked shops only AFTER facets load', async () => {
    window.history.replaceState(null, '', '/?shop=KSP&shop=Ghost')
    renderDashboard()
    // Initial fetch still carries the bookmarked shops (nothing discarded early).
    await waitFor(() =>
      expect(fetchDashboardMock).toHaveBeenCalledWith(
        expect.objectContaining({ shops: ['KSP', 'Ghost'] }),
      ),
    )
    // After the response (facets known), Ghost is dropped from the URL.
    await waitFor(() => expect(window.location.search).toBe('?shop=KSP'))
  })

  /* ── live listings contract (#157) ─────────────────────────────────── */

  it('renders listings in WIRE ORDER and marks Best by bestTrackedItemId, not by position', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    await within(region).findByText('KSP')
    // The fixture hands Bug first and the (Best) KSP listing second — the panel
    // must not reorder, and the chip must sit on KSP because the row said so.
    const shops = within(region)
      .getAllByText(/^(KSP|Bug)$/)
      .map((el) => el.textContent)
    expect(shops).toEqual(['Bug', 'KSP'])
    expect(within(region).getAllByText('Best')).toHaveLength(1)
    expect(within(region).getByText('Best').closest('.shop-color')).toHaveTextContent('KSP')
  })

  it('shows no Best chip when the row has no winning listing', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Framework Laptop 16' })
    await user.click(screen.getByRole('button', { name: 'Framework Laptop 16' }))
    const region = await screen.findByRole('region', { name: 'Framework Laptop 16' })
    await within(region).findByText('KSP')
    expect(within(region).queryByText('Best')).not.toBeInTheDocument()
  })

  it('renders converted + original at source, a stale-rate flag, an unconvertible original and "no current price"', async () => {
    fetchListingsMock.mockResolvedValue([
      {
        trackedItemId: 21,
        shopName: 'Amazon',
        url: 'https://www.amazon.com/dp/1',
        priceOriginal: '102.00',
        priceOriginalCurrency: 'USD',
        priceConverted: '382.00',
        priceConvertedCurrency: 'ILS',
        conversionStale: true,
        hidden: false,
        availability: 'AVAILABLE',
        lastChecked: new Date(Date.now() - 2 * HOUR).toISOString(),
      },
      {
        trackedItemId: 22,
        shopName: 'Argos',
        url: 'https://www.argos.co.uk/p/2',
        priceOriginal: '50.00',
        priceOriginalCurrency: 'GBP',
        priceConverted: null, // no rate → unconvertible
        priceConvertedCurrency: null,
        conversionStale: false,
        hidden: false,
        availability: 'UNKNOWN',
        lastChecked: new Date(Date.now() - 3 * HOUR).toISOString(),
      },
      {
        trackedItemId: 23,
        shopName: 'TMS',
        url: 'https://tms.co.il/item/3',
        priceOriginal: null, // gone cold past the carry-forward TTL
        priceOriginalCurrency: null,
        priceConverted: null,
        priceConvertedCurrency: null,
        conversionStale: false,
        hidden: false,
        availability: 'UNKNOWN',
        lastChecked: new Date(Date.now() - 9 * 24 * HOUR).toISOString(),
      },
    ] satisfies Listing[])
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    // 1. converted primary + original "at source" + stale flag
    expect(await within(region).findByText(/382/)).toBeInTheDocument()
    expect(within(region).getByText(/at source/)).toHaveTextContent(/102/)
    expect(within(region).getByText('Rate outdated')).toBeInTheDocument()
    // 2. unconvertible: the original alone, with the honest note
    expect(within(region).getByText(/50/)).toBeInTheDocument()
    expect(within(region).getByText('conversion unavailable')).toBeInTheDocument()
    // 3. no current observation: one neutral copy; "9 days ago" is the explanation
    expect(within(region).getByText('no current price')).toBeInTheDocument()
    expect(within(region).getByText(/9d ago|9 days ago/)).toBeInTheDocument()
  })

  it('re-syncs an expanded panel when the rows commit again — new winner, new order, new chip', async () => {
    const user = userEvent.setup()
    const { client } = renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    expect((await within(region).findByText('Best')).closest('.shop-color')).toHaveTextContent('KSP')

    // The next dashboard result moves the win to Bug (id 12) as an in-place
    // update (same product id order → commits silently), and the listings
    // endpoint now returns a DIFFERENT wire order: KSP first, then Bug at its
    // new price. The panel must adopt that order rather than keep the old one.
    fetchDashboardMock.mockResolvedValue(
      response(PRODUCTS.map((p) => (p.id === 1 ? { ...p, bestTrackedItemId: 12, bestPriceShop: 'Bug' } : p))),
    )
    fetchListingsMock.mockResolvedValue([
      LISTINGS[1],
      { ...LISTINGS[0], priceOriginal: '90.00', priceConverted: '90.00' },
    ])
    // A background refetch — what the 5-minute interval does in production.
    await client.refetchQueries({ queryKey: ['dashboard'] })

    // The effect invalidated the open panel: it refetched, re-rendered in the
    // new wire order, and the chip moved with the row's winner — which is now
    // the LAST row, so position can play no part in finding it.
    await waitFor(() => expect(within(region).getByText('Best').closest('.shop-color')).toHaveTextContent('Bug'))
    expect(
      within(region)
        .getAllByText(/^(KSP|Bug)$/)
        .map((el) => el.textContent),
    ).toEqual(['KSP', 'Bug'])
    expect(within(region).getByText(/90/)).toBeInTheDocument()
    expect(fetchListingsMock).toHaveBeenCalledTimes(2)
  })

  it('re-syncs an expanded panel even when the dashboard response is UNCHANGED', async () => {
    // A refresh where only a NON-winning listing moved leaves every row
    // byte-identical, so structural sharing keeps `result.data` reference-stable
    // and the committed snapshot never changes. Keying the invalidation on the
    // fetch timestamp instead is what keeps the open panel from going stale.
    const user = userEvent.setup()
    const { client } = renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    expect(await within(region).findByText(/120/)).toBeInTheDocument()

    // Same rows (a fresh but deeply-equal object), only the loser's price moved.
    fetchDashboardMock.mockResolvedValue(response(PRODUCTS))
    fetchListingsMock.mockResolvedValue([
      { ...LISTINGS[0], priceOriginal: '95.00', priceConverted: '95.00' },
      LISTINGS[1],
    ])
    await client.refetchQueries({ queryKey: ['dashboard'] })

    await waitFor(() => expect(within(region).getByText(/95/)).toBeInTheDocument())
    expect(fetchListingsMock).toHaveBeenCalledTimes(2)
    // The winner is unchanged, as the (unchanged) row still says.
    expect(within(region).getByText('Best').closest('.shop-color')).toHaveTextContent('KSP')
  })

  it('canonicalizes a bookmarked ?shop= spelling to the facet label, keeps the page, and presses the chip', async () => {
    fetchDashboardMock.mockResolvedValue(
      response(PRODUCTS, { page: { number: 2, size: 20, totalElements: 44, totalPages: 3 } }),
    )
    window.history.replaceState(null, '', '/?shop=ksp&page=2')
    renderDashboard()
    // First fetch carries the raw bookmark (the backend folds it anyway).
    await waitFor(() =>
      expect(fetchDashboardMock).toHaveBeenCalledWith(expect.objectContaining({ shops: ['ksp'], page: 2 })),
    )
    // After facets: URL re-spelled, page KEPT (a spelling rewrite is not a filter change).
    await waitFor(() => expect(window.location.search).toBe('?shop=KSP&page=2'))
    const chips = screen.getByRole('toolbar', { name: 'Filter by shop' })
    expect(within(chips).getByRole('button', { name: /KSP/ })).toHaveAttribute('aria-pressed', 'true')
  })

  it('clamps a bookmarked overflow page to totalPages (not 1)', async () => {
    fetchDashboardMock.mockResolvedValue(
      response(PRODUCTS, { page: { number: 9, size: 20, totalElements: 44, totalPages: 3 } }),
    )
    window.history.replaceState(null, '', '/?page=9')
    renderDashboard()
    await waitFor(() => expect(window.location.search).toBe('?page=3'))
  })

  it('disables entry animations under reduced motion (tile values render final immediately)', async () => {
    setMatchMediaMatches('(prefers-reduced-motion: reduce)', true)
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    // Count-up is disabled → the global total shows at once, no 0 start.
    const tiles = screen.getByLabelText('Tracking summary')
    expect(within(tiles).getByText('4')).toBeInTheDocument()
  })

  it('reacts to the reduced-motion preference changing AT RUNTIME', async () => {
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    dispatchMatchMediaChange('(prefers-reduced-motion: reduce)', true)
    await waitFor(() =>
      expect(within(screen.getByLabelText('Tracking summary')).getByText('4')).toBeInTheDocument(),
    )
  })

  /* ── hide / show listings (#250) ───────────────────────────────────── */

  const withHiddenBug = (): Listing[] => [{ ...LISTINGS[0], hidden: true }, LISTINGS[1]]

  async function openSonyPanel(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })
    await within(region).findByText('KSP')
    return region
  }

  it('hiding a shop removes its row at once and leaves an undo line in its slot', async () => {
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    expect(setListingHiddenMock).toHaveBeenCalledWith(1, 11, true)
    expect(await within(region).findByRole('button', { name: /^Undo hiding/ })).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: 'Hide KSP' })).not.toBeInTheDocument()
    // The row's own slot, not appended at the end: Bug is still handed first on the wire.
    expect(within(region).getByText('Bug')).toBeInTheDocument()
  })

  it('the undo line survives the refetch that flags the row hidden', async () => {
    // The trap: the refetch returns the row as hidden, and the filter that hides
    // rows would drop the undo line with it before its window is up.
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    fetchListingsMock.mockResolvedValue([LISTINGS[0], { ...LISTINGS[1], hidden: true }])

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    await waitFor(() => expect(fetchListingsMock.mock.calls.length).toBeGreaterThan(1))
    expect(within(region).getByRole('button', { name: /^Undo hiding/ })).toBeInTheDocument()
    // And it is NOT also listed in the hidden section — that would show it twice.
    expect(within(region).queryByRole('button', { name: /Hidden shops/ })).not.toBeInTheDocument()
  })

  it('Undo restores the shop', async () => {
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))
    const undo = await within(region).findByRole('button', { name: /^Undo hiding/ })

    await user.click(undo)

    expect(setListingHiddenMock).toHaveBeenLastCalledWith(1, 11, false)
  })

  it('moves focus to Undo after a KEYBOARD hide, since the button pressed no longer exists', async () => {
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    within(region).getByRole('button', { name: 'Hide KSP' }).focus()

    await user.keyboard('{Enter}')

    const undo = await within(region).findByRole('button', { name: /^Undo hiding/ })
    await waitFor(() => expect(undo).toHaveFocus())
  })

  it('leaves focus alone after a POINTER hide, so the window is not held open by it', async () => {
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    expect(await within(region).findByRole('button', { name: /^Undo hiding/ })).not.toHaveFocus()
  })

  it('holds the window open while Undo has focus, and lets it close once focus leaves', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setListingHiddenMock.mockResolvedValue(undefined)
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderDashboard()
      const region = await openSonyPanel(user)
      within(region).getByRole('button', { name: 'Hide KSP' }).focus()
      await user.keyboard('{Enter}')
      const undo = await within(region).findByRole('button', { name: /^Undo hiding/ })
      await waitFor(() => expect(undo).toHaveFocus())

      await act(async () => {
        await vi.advanceTimersByTimeAsync(UNDO_WINDOW_MS * 3)
      })
      // Parked on the control: the window cannot expire under the user.
      expect(within(region).getByRole('button', { name: /^Undo hiding/ })).toBeInTheDocument()

      await act(async () => {
        undo.blur()
        await vi.advanceTimersByTimeAsync(100)
      })

      expect(within(region).queryByRole('button', { name: /^Undo hiding/ })).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('announces the hide politely, because a visual swap says nothing to a screen reader', async () => {
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    const status = await within(region).findByRole('status')
    expect(status).toHaveTextContent('KSP hidden')
  })

  it('the undo line expires after its window and the shop joins the hidden section', async () => {
    // `shouldAdvanceTime` keeps findBy*/waitFor working on wall clock while still
    // letting the 6s window be jumped; plain fake timers would hang both.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setListingHiddenMock.mockResolvedValue(undefined)
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderDashboard()
      const region = await openSonyPanel(user)
      fetchListingsMock.mockResolvedValue([LISTINGS[0], { ...LISTINGS[1], hidden: true }])
      await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))
      await within(region).findByRole('button', { name: /^Undo hiding/ })

      await act(async () => {
        await vi.advanceTimersByTimeAsync(UNDO_WINDOW_MS + 100)
      })

      expect(within(region).queryByRole('button', { name: /^Undo hiding/ })).not.toBeInTheDocument()
      expect(within(region).getByRole('button', { name: /Hidden shop \(1\)/ })).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('a failed Undo keeps the row in place saying so, never silently in the collapsed section', async () => {
    setListingHiddenMock.mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('boom'))
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    await user.click(await within(region).findByRole('button', { name: /^Undo hiding/ }))

    // The alert sits in the row's own slot, and the control now offers a RETRY of
    // the restore — calling it "Undo hiding" would name the wrong operation.
    expect(await within(region).findByRole('alert')).toHaveTextContent("Couldn't restore KSP.")
    expect(within(region).getByRole('button', { name: 'Retry restoring KSP' })).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: /^Undo hiding/ })).not.toBeInTheDocument()
  })

  it('a failed keyboard Undo lands focus on the Retry that replaced it', async () => {
    setListingHiddenMock.mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('boom'))
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    within(region).getByRole('button', { name: 'Hide KSP' }).focus()
    await user.keyboard('{Enter}')
    const undo = await within(region).findByRole('button', { name: 'Undo hiding KSP' })
    await waitFor(() => expect(undo).toHaveFocus())

    // Enter on the focused Undo: the control unmounts for the restoring phase,
    // so focus must land on the Retry rather than falling to the document.
    await user.keyboard('{Enter}')

    const retry = await within(region).findByRole('button', { name: 'Retry restoring KSP' })
    await waitFor(() => expect(retry).toHaveFocus())
  })

  it('lists already-hidden shops in a collapsed section that says they are excluded', async () => {
    fetchListingsMock.mockResolvedValue(withHiddenBug())
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)

    // Not rendered as a row…
    expect(within(region).queryByText('Bug')).not.toBeInTheDocument()
    // …and the section names the count and the consequence.
    const toggle = within(region).getByRole('button', { name: /Hidden shop \(1\)/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(within(region).getByText('excluded from price comparisons')).toBeInTheDocument()

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(within(region).getByText('Bug')).toBeInTheDocument()
    // Best stays on the visible KSP row and never appears on the hidden one; the
    // hidden row's control is Restore, not a second Hide.
    expect(within(region).getAllByText('Best')).toHaveLength(1)
    expect(within(region).getByText('Best').closest('.shop-color')).toHaveTextContent('KSP')
    expect(within(region).getByRole('button', { name: /^Restore/ })).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: 'Hide Bug' })).not.toBeInTheDocument()
  })

  it('Restore from the hidden section brings the shop back', async () => {
    fetchListingsMock.mockResolvedValue(withHiddenBug())
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    await user.click(within(region).getByRole('button', { name: /Hidden shop \(1\)/ }))

    await user.click(within(region).getByRole('button', { name: /^Restore/ }))

    expect(setListingHiddenMock).toHaveBeenCalledWith(1, 12, false)
  })

  it('shows hidden shops directly when none are left visible, rather than behind a toggle', async () => {
    fetchListingsMock.mockResolvedValue(LISTINGS.map((l) => ({ ...l, hidden: true })))
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })
    await user.click(screen.getByRole('button', { name: 'Sony WH-1000XM5' }))
    const region = await screen.findByRole('region', { name: 'Sony WH-1000XM5' })

    expect(await within(region).findByText(/All shops are hidden/)).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: /Hidden shops/ })).not.toBeInTheDocument()
    expect(within(region).getAllByRole('button', { name: /^Restore/ })).toHaveLength(2)
    // Distinct from a product the catalogue has no listings for at all.
    expect(within(region).queryByText(/No shops tracked for this product yet/)).not.toBeInTheDocument()
  })

  it('a reorder caused by a hide lands at once, not behind "Prices updated"', async () => {
    // The background-reorder guard exists to stop rows moving under a READER; someone who
    // just hid a shop asked for the reorder, so it must commit without a second click.
    const [sony, airpods] = PRODUCTS
    fetchDashboardMock.mockResolvedValue(response([sony, airpods]))
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)
    fetchDashboardMock.mockResolvedValue(response([airpods, sony]))

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    await waitFor(() => {
      const names = screen.getAllByRole('button', { name: /Sony WH-1000XM5|Apple AirPods Pro 2/ })
      expect(names.map((n) => n.textContent)).toEqual(['Apple AirPods Pro 2', 'Sony WH-1000XM5'])
    })
    expect(screen.queryByRole('button', { name: /Prices updated/ })).not.toBeInTheDocument()
  })

  it('two overlapping hides both commit their reorder, with no owed commit left behind', async () => {
    // A count would assume one dashboard response per mutation, which coalescing
    // breaks; a leaked count would then let a LATER background reorder through.
    const [sony, airpods] = PRODUCTS
    fetchDashboardMock.mockResolvedValue(response([sony, airpods]))
    // Both hides are held IN FLIGHT and released together, so their
    // invalidations can coalesce into one refetch — the case a per-mutation
    // count got wrong. Resolving each click in turn would serialise them and
    // test nothing.
    const release: Array<() => void> = []
    setListingHiddenMock.mockImplementation(
      () => new Promise<void>((resolve) => release.push(() => resolve())),
    )
    const user = userEvent.setup()
    const { client } = renderDashboard()
    const region = await openSonyPanel(user)
    fetchDashboardMock.mockResolvedValue(response([airpods, sony]))

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))
    await user.click(within(region).getByRole('button', { name: 'Hide Bug' }))
    expect(release).toHaveLength(2)
    await act(async () => {
      release.forEach((resolve) => resolve())
    })

    await waitFor(() => {
      const names = screen.getAllByRole('button', { name: /Sony WH-1000XM5|Apple AirPods Pro 2/ })
      expect(names.map((n) => n.textContent)).toEqual(['Apple AirPods Pro 2', 'Sony WH-1000XM5'])
    })
    expect(screen.queryByRole('button', { name: /Prices updated/ })).not.toBeInTheDocument()

    // Nothing owed: a genuine BACKGROUND reorder now parks, as it must.
    fetchDashboardMock.mockResolvedValue(response([sony, airpods]))
    await act(async () => {
      await client.refetchQueries({ queryKey: ['dashboard'] })
    })
    expect(await screen.findByRole('button', { name: /Prices updated/ })).toBeInTheDocument()
  })

  it('commits a hide-driven reorder whose response arrives LATER, not in the same batch', async () => {
    // The case instant mocks cannot show. With a deferred response the render
    // that arms the marker still holds the cached data, so anything consumed by
    // "a render happened" is gone before the refetch lands and the reorder parks.
    const [sony, airpods] = PRODUCTS
    fetchDashboardMock.mockResolvedValue(response([sony, airpods]))
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)

    let releaseDashboard: (() => void) | undefined
    fetchDashboardMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseDashboard = () => resolve(response([airpods, sony]))
        }),
    )

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))
    await waitFor(() => expect(releaseDashboard).toBeDefined())
    await act(async () => {
      releaseDashboard?.()
    })

    await waitFor(() => {
      const names = screen.getAllByRole('button', { name: /Sony WH-1000XM5|Apple AirPods Pro 2/ })
      expect(names.map((n) => n.textContent)).toEqual(['Apple AirPods Pro 2', 'Sony WH-1000XM5'])
    })
    expect(screen.queryByRole('button', { name: /Prices updated/ })).not.toBeInTheDocument()
  })

  it('a commit mark does not survive a view change, so the new view still parks background reorders', async () => {
    // Filtering mid-mutation swaps the query underneath. A mark that was just a
    // number would be compared against the NEW query's counter and could wave an
    // unrelated reorder through.
    const [sony, airpods] = PRODUCTS
    fetchDashboardMock.mockResolvedValue(response([sony, airpods]))
    setListingHiddenMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    const { client } = renderDashboard()
    const region = await openSonyPanel(user)

    // Warm the filtered view's counter ABOVE the unfiltered one's, which is what
    // makes an unbound mark dangerous: a stale count from a freshly-loaded view
    // is satisfied by any response in a view that has been fetched more often.
    const chips = screen.getByRole('toolbar', { name: 'Filter by shop' })
    for (let visit = 0; visit < 3; visit += 1) {
      await user.click(within(chips).getByRole('button', { name: /KSP/ }))
      await waitFor(() => expect(window.location.search).toBe('?shop=KSP'))
      await user.click(within(chips).getByRole('button', { name: /KSP/ }))
      await waitFor(() => expect(window.location.search).toBe(''))
    }

    // Hold the UNFILTERED refetch open so the mark is still armed when the view
    // changes; the filtered view keeps resolving, so only its counter advances.
    fetchDashboardMock.mockImplementation((q: { shops?: string[] }) =>
      q.shops === undefined
        ? new Promise(() => undefined)
        : Promise.resolve(response([sony, airpods])),
    )
    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))
    await user.click(within(chips).getByRole('button', { name: /KSP/ }))
    await waitFor(() => expect(window.location.search).toBe('?shop=KSP'))
    await screen.findByRole('button', { name: 'Sony WH-1000XM5' })

    // A genuine background reorder in the NEW view must still park.
    fetchDashboardMock.mockResolvedValue(response([airpods, sony]))
    await act(async () => {
      await client.refetchQueries({ queryKey: ['dashboard'] })
    })

    expect(await screen.findByRole('button', { name: /Prices updated/ })).toBeInTheDocument()
  })

  it('each undo window runs on its own clock; focusing one does not hold the others open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setListingHiddenMock.mockResolvedValue(undefined)
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderDashboard()
      const region = await openSonyPanel(user)

      // Both by pointer, so neither takes focus on its own; then park on one.
      // (Clicking a second control would blur the first, which correctly ends
      // its protection — the hold has to be tested without an intervening click.)
      await user.click(within(region).getByRole('button', { name: 'Hide Bug' }))
      await within(region).findByRole('button', { name: 'Undo hiding Bug' })
      await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))
      await within(region).findByRole('button', { name: 'Undo hiding KSP' })

      await act(async () => {
        within(region).getByRole('button', { name: 'Undo hiding Bug' }).focus()
        await vi.advanceTimersByTimeAsync(UNDO_WINDOW_MS + 100)
      })

      // The focused row is held open; the unfocused one expired on its own clock.
      expect(within(region).getByRole('button', { name: 'Undo hiding Bug' })).toBeInTheDocument()
      expect(within(region).queryByRole('button', { name: 'Undo hiding KSP' })).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('a failed hide surfaces an alert under the row and leaves the panel usable', async () => {
    setListingHiddenMock.mockRejectedValue(new Error('boom'))
    const user = userEvent.setup()
    renderDashboard()
    const region = await openSonyPanel(user)

    await user.click(within(region).getByRole('button', { name: 'Hide KSP' }))

    expect(await within(region).findByRole('alert')).toHaveTextContent("Couldn't hide KSP")
    expect(within(region).getByRole('button', { name: 'Hide KSP' })).toBeEnabled()
  })

  it('a product with every shop hidden reads "No visible shops", never "0 of 0 in stock"', async () => {
    fetchDashboardMock.mockResolvedValue(
      response([
        product({
          id: 5,
          name: 'Hidden-only lamp',
          bestPriceConverted: null,
          bestPriceShop: null,
          bestTrackedItemId: null,
          availability: { status: 'UNKNOWN', availableCount: 0, total: 0 },
        }),
      ]),
    )
    renderDashboard()
    await screen.findByRole('button', { name: 'Hidden-only lamp' })
    expect(screen.getAllByText('No visible shops')).toHaveLength(2)
  })
})
