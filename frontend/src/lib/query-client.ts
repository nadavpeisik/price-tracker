import { QueryCache, QueryClient } from '@tanstack/react-query'
import { isApiError } from '@/lib/api-error'
import type { Me } from '@/lib/types'

/**
 * The app's QueryClient (#248), extracted so tests run the production rules.
 *
 * ONE global rule: a 401 from any query means the BFF session is gone, so
 * the `['me']` entry is flipped to anonymous. That unmounts the shell and
 * the dashboard through `AuthGate`, which stops their observers and the
 * 5-minute refetch. Deliberately `setQueryData`, not `removeQueries`:
 * removing entries under still-mounted observers makes them refetch against
 * the dead session in the tick before the gate unmounts the tree. The stale
 * entries are inert until the sign-in round trip, which is a full-page
 * navigation and therefore a fresh client.
 *
 * No 403 rule (not-admitted is read off one known query, the account) and no
 * MutationCache rule (the one mutation, logout, maps its own 401).
 */
export function createQueryClient(): QueryClient {
  const anonymous: Me = { status: 'anonymous' }
  const queryClient: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error) => {
        if (isApiError(error) && error.status === 401) {
          queryClient.setQueryData(['me'], anonymous)
        }
      },
    }),
    defaultOptions: {
      queries: {
        // 4xx is terminal at once; network failures and 5xx keep TanStack's
        // default three attempts with backoff.
        retry: (count, error) => !(isApiError(error) && error.status < 500) && count < 3,
      },
    },
  })
  return queryClient
}
