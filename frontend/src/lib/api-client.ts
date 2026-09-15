/**
 * API client (#144): the single module every dashboard fetch goes through.
 *
 * - Base path centralized: since #248 the browser never calls the backend
 *   directly — every `/api` call goes to the BFF's proxy at `/bff/api`,
 *   which attaches the bearer token from the cookie session. In dev the Vite
 *   proxy forwards `/bff` to the BFF on :8082; in prod Caddy does (#263).
 * - Owns the UI ↔ backend serialization: the adapter (toBackendParams) is
 *   the only place that knows the wire param names, and it is unit-tested.
 *   Pagination needs NO translation — #146 settled on 1-based `page` at the
 *   HTTP boundary in both directions, so the UI page, the URL, the request
 *   and the response all say the same number. (Spring Data's 0-based
 *   Pageable convention is internal to that library; the dashboard endpoint
 *   deliberately does not expose it, and `?page=0` is a 400.)
 * - Mock mode: `import.meta.env.DEV && VITE_USE_MOCK` routes to the mock
 *   client through a STATIC-PATH dynamic import inside a DEV-gated branch,
 *   which Rollup dead-code-eliminates from production bundles (never a
 *   variable-path import — that still emits mock chunks).
 */
import { ApiError } from '@/lib/api-error'
import { readXsrfToken } from '@/lib/csrf'
import type { DashboardQuery, DashboardResponse, Listing } from '@/lib/types'

export const API_BASE = '/bff/api'

/** The dashboard query endpoint (#146); the mock implements the same contract. */
const DASHBOARD_PATH = `${API_BASE}/tracked-products`

/**
 * NOTE: the fetch functions below repeat the `import.meta.env.DEV && …`
 * expression LITERALLY instead of sharing a helper — Vite substitutes
 * `import.meta.env.DEV` with `false` at build time only when it appears
 * directly in the branch condition, which is what lets Rollup dead-code-
 * eliminate the mock imports (a function call is opaque to it and would
 * emit mock chunks into the prod bundle). Mock mode is a data-source switch
 * inside this module only; nothing else in the app knows about it (#157).
 */

/** UI query → backend query string; `page` is 1-based on both sides. */
export function toBackendParams(query: DashboardQuery): URLSearchParams {
  const params = new URLSearchParams()
  const search = query.search?.trim()
  if (search) params.set('search', search)
  for (const shop of query.shops ?? []) params.append('shops', shop)
  params.set('sort', query.sort)
  params.set('page', String(query.page))
  params.set('size', String(query.size))
  return params
}

/**
 * The one request helper (#248), shared with `auth-client.ts` so there is
 * exactly one CSRF rule, one credentials rule and one error shape. Takes a
 * ROOT-relative URL (`/bff/...`): the session cookie is first-party because
 * the SPA and the BFF share an origin through the proxy, so `same-origin` is
 * the whole credentials story. Non-2xx throws `ApiError` (status only). A 2xx
 * with no body (the 201/204 of `POST /bff/api/me`, #249) resolves to
 * `undefined`; the BFF forwards no `Content-Length`, so the body text, not a
 * header, is what says whether there is JSON to parse.
 */
export async function request<T>(
  url: string,
  init: { method?: 'GET' | 'POST' | 'PATCH'; body?: unknown } = {},
): Promise<T> {
  const method = init.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  // The BFF checks CSRF on every method it proxies as a mutation, so the rule
  // is "anything but GET" rather than a list of verbs.
  if (method !== 'GET') {
    const token = readXsrfToken()
    if (token !== null) headers['X-XSRF-TOKEN'] = token
  }
  const body = init.body === undefined ? undefined : JSON.stringify(init.body)
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(url, { method, headers, body, credentials: 'same-origin' })
  if (!response.ok) {
    throw new ApiError(response.status, response.statusText)
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export async function fetchDashboard(query: DashboardQuery): Promise<DashboardResponse> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    const { mockFetchDashboard } = await import('@/mocks/mock-client')
    return mockFetchDashboard(query)
  }
  return request<DashboardResponse>(`${DASHBOARD_PATH}?${toBackendParams(query)}`)
}

export async function fetchListings(productId: number): Promise<Listing[]> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    const { mockFetchListings } = await import('@/mocks/mock-client')
    return mockFetchListings(productId)
  }
  // Live: GET /api/products/{id}/listings (#157) — already ordered and
  // FX-normalized into the display currency by the backend; rendered as
  // received. No displayCurrency param: both endpoints fall back to the same
  // per-user preference / configured default (#248), so a row and its panel
  // agree by construction.
  return request<Listing[]>(`${API_BASE}/products/${productId}/listings`)
}

/**
 * Hide or show one shop on the caller's own dashboard (#250). 204 on success;
 * a 404 means the product is not the caller's or the listing is not under it.
 * The caller invalidates the panel and the dashboard: hiding moves the row's
 * best price and shop count, which only the backend computes.
 */
export async function setListingHidden(productId: number, trackedItemId: number, hidden: boolean): Promise<void> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    const { mockSetListingHidden } = await import('@/mocks/mock-client')
    return mockSetListingHidden(productId, trackedItemId, hidden)
  }
  return request<void>(`${API_BASE}/tracked-products/${productId}/listings/${trackedItemId}`, {
    method: 'PATCH',
    body: { hidden },
  })
}
