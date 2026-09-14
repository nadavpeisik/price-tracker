import { useMutation } from '@tanstack/react-query'
import { logout } from '@/lib/auth-client'
import { navigateTo } from '@/lib/navigation'

/**
 * Sign out (#248): POST the BFF logout, then leave for the Auth0 end-session
 * URL it returns. Shared by the shell and the not-admitted screen. No
 * `queryClient.clear()` first — it would reset the mounted `['me']` observer
 * to pending and flash the skeleton before the browser leaves, and the
 * full-page navigation tears the cache down anyway.
 */
export function useSignOut() {
  const mutation = useMutation({ mutationFn: logout, onSuccess: (url) => navigateTo(url) })
  return { signOut: () => mutation.mutate(), isPending: mutation.isPending, isError: mutation.isError }
}
