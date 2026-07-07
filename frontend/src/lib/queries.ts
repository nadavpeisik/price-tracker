import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { fetchDashboard, fetchListings } from '@/lib/api-client'
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
