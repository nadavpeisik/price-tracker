import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMe, fetchAccount, logout } from '@/lib/auth-client'
import { ApiError } from '@/lib/api-error'

/** A 200 carrying `body` as JSON — what every happy-path fetch stub returns. */
const okJson = (body: unknown): Response =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

/** An empty response with `code`, for the failure branches; only the status is ever read. */
const status = (code: number): Response => new Response('', { status: code, statusText: 'x' })

const user = { name: 'Nadav', email: 'n@example.com' }

/** The BFF session routes (#248). Vitest runs without VITE_USE_MOCK: the live branch. */
describe('auth-client', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    // jsdom will not store a `__Host-` cookie on an http origin, so read it through the getter.
    vi.spyOn(document, 'cookie', 'get').mockReturnValue('__Host-XSRF-TOKEN=csrf-1')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('fetchMe: 200 is signed-in with the user', async () => {
    vi.mocked(fetch).mockResolvedValue(okJson(user))
    await expect(fetchMe()).resolves.toEqual({ status: 'signed-in', user })
    expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/bff/me')
  })

  it('fetchMe: 401 is the anonymous state, not an error', async () => {
    vi.mocked(fetch).mockResolvedValue(status(401))
    await expect(fetchMe()).resolves.toEqual({ status: 'anonymous' })
  })

  it('fetchMe: any other failure rejects (the BFF is down)', async () => {
    vi.mocked(fetch).mockResolvedValue(status(500))
    await expect(fetchMe()).rejects.toBeInstanceOf(ApiError)
  })

  it('fetchAccount: GET /bff/api/me → the display currency', async () => {
    vi.mocked(fetch).mockResolvedValue(okJson({ displayCurrency: 'USD' }))
    await expect(fetchAccount()).resolves.toEqual({ displayCurrency: 'USD' })
    expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/bff/api/me')
  })

  it('fetchAccount: 403 (not admitted) rejects with the status for the gate to read', async () => {
    vi.mocked(fetch).mockResolvedValue(status(403))
    const error = await fetchAccount().catch((e: unknown) => e)
    expect((error as ApiError).status).toBe(403)
  })

  it('logout: POSTs with the CSRF header and returns the Auth0 logout URL', async () => {
    vi.mocked(fetch).mockResolvedValue(okJson({ logoutUrl: 'https://tenant/oidc/logout?x' }))

    await expect(logout()).resolves.toBe('https://tenant/oidc/logout?x')

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toBe('/bff/logout')
    expect((init as RequestInit).method).toBe('POST')
    expect((init as RequestInit).headers).toMatchObject({ 'X-XSRF-TOKEN': 'csrf-1' })
  })

  it('logout: 401 (session already gone) resolves to / — reloading lands on sign-in', async () => {
    vi.mocked(fetch).mockResolvedValue(status(401))
    await expect(logout()).resolves.toBe('/')
  })

  it('logout: other failures reject', async () => {
    vi.mocked(fetch).mockResolvedValue(status(503))
    await expect(logout()).rejects.toBeInstanceOf(ApiError)
  })
})
