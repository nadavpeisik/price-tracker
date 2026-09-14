import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { fetchDashboard, fetchListings } from '@/lib/api-client'
import { fetchMe, fetchAccount } from '@/lib/auth-client'
import type { DashboardQuery } from '@/lib/types'

export const PAGE_SIZE = 20

/**
 * Background refetch keeps an idle dashboard fresh (and lets live price
 * drops celebrate). Deliberately LONG (#144): against a heavy live
 * aggregate a short interval would strain the backend — revisit with a
 * lightweight endpoint/SSE if real-time is ever needed.
 */
export const DASHBOARD_REFETCH_MS = 5 * 60_000

export function dashboardQueryOptions(query: DashboardQuery) {
  return queryOptions({
    // Spread the query into the key so every param combination caches
    // separately (the object is JSON-stable: fixed field set).
    queryKey: ['dashboard', query] as const,
    queryFn: () => fetchDashboard(query),
    // v5: keepPreviousData VALUE as placeholderData — the current page stays
    // visible during a re-sort/filter instead of flashing a skeleton.
    placeholderData: keepPreviousData,
    refetchInterval: DASHBOARD_REFETCH_MS,
  })
}

/**
 * Lazy per-product listings — fetched on row expand only, kept in its OWN
 * query (never merged into the paged dashboard cache).
 */
export function listingsQueryOptions(productId: number, enabled: boolean) {
  return queryOptions({
    queryKey: ['product-listings', productId] as const,
    queryFn: () => fetchListings(productId),
    enabled,
    staleTime: 60_000,
  })
}

/**
 * Who is signed in (#248). Never stale and never refetched on focus: a
 * session is invalidated by a 401 on real traffic (see `query-client.ts`),
 * not by polling — the dashboard's 5-minute refetch IS that traffic. No
 * retries: a 401 is the anonymous answer, and a BFF outage should show the
 * error at once rather than after three attempts.
 */
export function meQueryOptions() {
  return queryOptions({
    queryKey: ['me'] as const,
    queryFn: fetchMe,
    staleTime: Infinity,
    retry: false,
    refetchOnWindowFocus: false,
  })
}

/**
 * The backend's view of the account (#248), fetched only once signed in. Its
 * 403 is how the gate learns the identity is not admitted.
 *
 * Cached for the session on purpose (raised in three review rounds): the money
 * endpoints re-read the stored preference per request, so an out-of-band change
 * would show new prices under the old label — but nothing in the app writes the
 * preference, and the settings screen that adds a writer invalidates
 * `['account']` alongside the money queries.
 */
export function accountQueryOptions(enabled: boolean) {
  return queryOptions({
    queryKey: ['account'] as const,
    queryFn: fetchAccount,
    enabled,
    staleTime: Infinity,
    retry: false,
  })
}
