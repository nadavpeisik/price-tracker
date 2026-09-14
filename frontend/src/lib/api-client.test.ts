import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchDashboard, fetchListings, request, toBackendParams } from '@/lib/api-client'
import { ApiError } from '@/lib/api-error'
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

/** A 200 carrying `body` as JSON — what every happy-path fetch stub returns. */
const okJson = (body: unknown): Response =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

/**
 * The live paths (#157, via the BFF proxy since #248). Vitest runs without
 * VITE_USE_MOCK, so both fetch functions take the live branch — the mock
 * branch is dead-code-eliminated exactly as it is in a production build.
 */
describe('live fetches', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetchDashboard hits GET /bff/api/tracked-products with the serialized query', async () => {
    vi.mocked(fetch).mockResolvedValue(okJson({ items: [] }))

    await fetchDashboard({ sort: 'name', page: 2, size: 20, shops: ['KSP'] })

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toBe('/bff/api/tracked-products?shops=KSP&sort=name&page=2&size=20')
    expect((init as RequestInit).headers).toEqual({ Accept: 'application/json' })
  })

  it('fetchListings hits GET /bff/api/products/{id}/listings and returns the body as-is (wire order kept)', async () => {
    const wire = [{ trackedItemId: 2 }, { trackedItemId: 1 }]
    vi.mocked(fetch).mockResolvedValue(okJson(wire))

    const listings = await fetchListings(42)

    expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/bff/api/products/42/listings')
    expect(listings.map((l) => l.trackedItemId)).toEqual([2, 1])
  })
})

/**
 * The one request helper (#248): first-party cookie session, CSRF header on
 * mutations only, and a status-carrying error the UI can branch on.
 */
describe('request', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('sends the session cookie (same-origin) and asks for JSON', async () => {
    vi.mocked(fetch).mockResolvedValue(okJson({}))

    await request('/bff/me')

    const init = vi.mocked(fetch).mock.calls[0][1] as RequestInit
    expect(init.credentials).toBe('same-origin')
    expect(init.method).toBe('GET')
    expect(init.headers).toEqual({ Accept: 'application/json' })
  })

  it('adds the CSRF header from the cookie on POST, and not on GET', async () => {
    // jsdom will not store a `__Host-` cookie on an http origin, so read it through the getter.
    vi.spyOn(document, 'cookie', 'get').mockReturnValue('other=1; __Host-XSRF-TOKEN=abc-123')
    vi.mocked(fetch).mockImplementation(() => Promise.resolve(okJson({})))

    await request('/bff/logout', { method: 'POST' })
    await request('/bff/me')

    const [, postInit] = vi.mocked(fetch).mock.calls[0]
    const [, getInit] = vi.mocked(fetch).mock.calls[1]
    expect((postInit as RequestInit).headers).toEqual({ Accept: 'application/json', 'X-XSRF-TOKEN': 'abc-123' })
    expect((getInit as RequestInit).headers).toEqual({ Accept: 'application/json' })
  })

  it('rejects with an ApiError carrying the status on a non-2xx', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('nope', { status: 500, statusText: 'Server Error' }))

    const error = await request('/bff/api/x').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(500)
    expect((error as ApiError).message).toBe('Request failed: 500 Server Error')
  })
})
