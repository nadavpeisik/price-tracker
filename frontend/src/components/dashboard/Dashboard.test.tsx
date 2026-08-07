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

const LISTINGS: Listing[] = [
  {
    trackedItemId: 11,
    shop: 'KSP',
    url: 'https://ksp.co.il/web/item/1',
    price: '100.00',
    currency: 'ILS',
    availability: 'AVAILABLE',
    lastChecked: new Date(Date.now() - 2 * HOUR).toISOString(),
    // 2 hours ago → "checked 2h ago"
  },
  {
    trackedItemId: 12,
    shop: 'Bug',
    url: 'javascript:alert(1)', // scheme-guard fixture
    price: '120.00',
    currency: 'ILS',
    availability: 'UNKNOWN',
    lastChecked: null,
  },
]

/* ── harness ─────────────────────────────────────────────────────────── */

function renderDashboard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <Dashboard />
      </TooltipProvider>
    </QueryClientProvider>,
  )
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
