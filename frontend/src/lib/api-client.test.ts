import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchDashboard, fetchListings, toBackendParams } from '@/lib/api-client'
import type { DashboardQuery } from '@/lib/types'

/**
 * The adapter owns the UI↔backend serialization (#144): repeated shops,
 * trimmed search, param names. Pagination is 1-based end to end (#146), so
 * `page` passes through untouched — the absence of arithmetic is the point.
 */
describe('toBackendParams', () => {
  const base: DashboardQuery = { sort: 'biggest7dDrop', page: 1, size: 20 }

  it('sends the UI page unchanged — pagination is 1-based on both sides', () => {
    expect(toBackendParams({ ...base, page: 1 }).get('page')).toBe('1')
    expect(toBackendParams({ ...base, page: 3 }).get('page')).toBe('3')
  })

  it('serializes shops as repeated params', () => {
    const params = toBackendParams({ ...base, shops: ['KSP', 'Ivory'] })
    expect(params.getAll('shops')).toEqual(['KSP', 'Ivory'])
  })

  it('omits empty search and trims whitespace', () => {
    expect(toBackendParams(base).has('search')).toBe(false)
    expect(toBackendParams({ ...base, search: '   ' }).has('search')).toBe(false)
    expect(toBackendParams({ ...base, search: ' sony ' }).get('search')).toBe('sony')
  })

  it('carries sort and size through', () => {
    const params = toBackendParams({ ...base, sort: 'lowestCurrentPrice', size: 50 })
    expect(params.get('sort')).toBe('lowestCurrentPrice')
    expect(params.get('size')).toBe('50')
  })
})

/**
 * The live paths (#157). Vitest runs without VITE_USE_MOCK, so both fetch
 * functions take the live branch — the mock branch is dead-code-eliminated
 * exactly as it is in a production build.
 */
describe('live fetches', () => {
  const okJson = (body: unknown) =>
    new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetchDashboard hits GET /api/tracked-products with the serialized query', async () => {
    vi.mocked(fetch).mockResolvedValue(okJson({ items: [] }))

    await fetchDashboard({ sort: 'name', page: 2, size: 20, shops: ['KSP'] })

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toBe('/api/tracked-products?shops=KSP&sort=name&page=2&size=20')
    expect((init as RequestInit).headers).toEqual({ Accept: 'application/json' })
  })

  it('fetchListings hits GET /api/products/{id}/listings and returns the body as-is (wire order kept)', async () => {
    const wire = [{ trackedItemId: 2 }, { trackedItemId: 1 }]
    vi.mocked(fetch).mockResolvedValue(okJson(wire))

    const listings = await fetchListings(42)

    expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/api/products/42/listings')
    expect(listings.map((l) => l.trackedItemId)).toEqual([2, 1])
  })

  it('rejects on a non-2xx status — this is what drives the row-level retry UI', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('nope', { status: 500, statusText: 'Server Error' }))

    await expect(fetchListings(42)).rejects.toThrow('Request failed: 500 Server Error')
  })
})
