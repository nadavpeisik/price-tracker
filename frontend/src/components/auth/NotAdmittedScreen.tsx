import { Button } from '@/components/ui/button'
import { BrandMark } from '@/components/auth/BrandMark'
import { useSignOut } from '@/hooks/use-sign-out'

/**
 * Signed in, not admitted (#248): the backend has no `app_user` row for this
 * identity — not invited, or a fresh database not yet relinked. Frontend-owned
 * copy, never the ProblemDetail `detail`. No Retry: nothing the user can do
 * fixes it. Sign out is here because the shell is not mounted in this state
 * and without it the user would be stuck.
 */
export function NotAdmittedScreen() {
  const { signOut, isPending, isError } = useSignOut()
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-6 px-5 py-16 text-center">
      <BrandMark />
      <h1 className="font-display text-xl font-bold">This account isn't set up for PriceHunt yet</h1>
      <p className="text-sm text-ink-muted">Ask for an invite, then sign in again with the same account.</p>
      <Button variant="outline" onClick={signOut} disabled={isPending}>
        Sign out
      </Button>
      {isError && (
        <p role="alert" className="text-sm text-ink-muted">
          Couldn't sign out — try again.
        </p>
      )}
    </main>
  )
}
