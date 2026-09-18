import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
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
 * The same rule on the MutationCache (#250, the first data mutation): a hide
 * on an expired session must sign the user out like a query would, not strand
 * them behind an inline error until the next refetch. Logout's own 401 still
 * resolves as "done" inside `logout()` before it could reach here.
 *
 * No 403 rule (not-admitted is read off one known query, the account).
 */
export function createQueryClient(): QueryClient {
  const anonymous: Me = { status: 'anonymous' }
  const signOutOn401 = (error: unknown) => {
    if (isApiError(error) && error.status === 401) {
      queryClient.setQueryData(['me'], anonymous)
    }
  }
  const queryClient: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: signOutOn401 }),
    mutationCache: new MutationCache({ onError: signOutOn401 }),
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
