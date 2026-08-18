import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { TooltipProvider } from '@/components/ui/tooltip'
import { dispatchMatchMediaChange, resetMatchMedia, setMatchMediaMatches } from '@/test/setup'
import { Dashboard } from '@/components/dashboard/Dashboard'
import type { DashboardResponse, Listing, TrackedProduct } from '@/lib/types'

/* ── api-client mock (the Dashboard's only IO boundary) ─────────────── */

const { fetchDashboardMock, fetchListingsMock } = vi.hoisted(() => ({
  fetchDashboardMock: vi.fn(),
  fetchListingsMock: vi.fn(),
}))

vi.mock('@/lib/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api-client')>()),
  fetchDashboard: fetchDashboardMock,
  fetchListings: fetchListingsMock,
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
    // update (same id order → commits silently), and the listings endpoint
    // now returns Bug first at its new price.
    fetchDashboardMock.mockResolvedValue(
      response(PRODUCTS.map((p) => (p.id === 1 ? { ...p, bestTrackedItemId: 12, bestPriceShop: 'Bug' } : p))),
    )
    fetchListingsMock.mockResolvedValue([{ ...LISTINGS[0], priceOriginal: '90.00', priceConverted: '90.00' }, LISTINGS[1]])
    // A background refetch — what the 5-minute interval does in production.
    await client.refetchQueries({ queryKey: ['dashboard'] })

    // The commit effect invalidated the open panel: it refetched, re-rendered
    // in the new wire order, and the chip moved with the row's winner.
    await waitFor(() => expect(within(region).getByText('Best').closest('.shop-color')).toHaveTextContent('Bug'))
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
})
