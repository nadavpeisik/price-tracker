/**
 * API client (#144): the single module every dashboard fetch goes through.
 *
 * - Base path centralized (default '/api', dev-proxied to Spring) so a
 *   future non-proxied deployment is a one-line change.
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
import type { DashboardQuery, DashboardResponse, Listing } from '@/lib/types'

const API_BASE = '/api'

/**
 * Endpoint name is #146's to finalize — the mock implements the same
 * contract, so only these paths change at live wiring time.
 */
const DASHBOARD_PATH = `${API_BASE}/tracked-products`

/**
 * NOTE: the fetch functions below repeat the `import.meta.env.DEV && …`
 * expression LITERALLY instead of calling this helper — Vite substitutes
 * `import.meta.env.DEV` with `false` at build time only when it appears
 * directly in the branch condition, which is what lets Rollup dead-code-
 * eliminate the mock imports (a function call is opaque to it and would
 * emit mock chunks into the prod bundle).
 */
export const isMockMode = (): boolean =>
  import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true'

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

async function liveFetch<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export async function fetchDashboard(query: DashboardQuery): Promise<DashboardResponse> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    const { mockFetchDashboard } = await import('@/mocks/mock-client')
    return mockFetchDashboard(query)
  }
  return liveFetch<DashboardResponse>(`${DASHBOARD_PATH}?${toBackendParams(query)}`)
}

export async function fetchListings(productId: number): Promise<Listing[]> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    const { mockFetchListings } = await import('@/mocks/mock-client')
    return mockFetchListings(productId)
  }
  // Live shape: GET /api/products/{id} → ProductDetailResponse.trackedItems,
  // adapted to Listing[] when #146 wiring lands. Until then this path serves
  // the (unmounted-in-prod) live mode only.
  return liveFetch<Listing[]>(`${API_BASE}/products/${productId}/listings`)
}
