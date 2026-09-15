import { Button } from '@/components/ui/button'
import { BrandMark } from '@/components/auth/BrandMark'
import { useSignOut } from '@/hooks/use-sign-out'

/**
 * Signed in, not admitted (#248, #249): the backend has no `app_user` row for
 * this identity and no open invitation named its verified email when the
 * account fetch tried to create one. Frontend-owned copy, never the
 * ProblemDetail `detail`. No Retry: once an invitation exists, a reload
 * redeems it; a later-verified email needs a fresh token, so that path is
 * sign out and back in. Sign out is here because the shell is not mounted in
 * this state and without it the user would be stuck.
 */
export function NotAdmittedScreen() {
  const { signOut, isPending, isError } = useSignOut()
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-6 px-5 py-16 text-center">
      <BrandMark />
      <h1 className="font-display text-xl font-bold">This account isn't set up for PriceHunt yet</h1>
      <p className="text-sm text-ink-muted">
        Ask for an invite for this account's email address, then reload. If you verified the address
        after signing in, sign out and back in first.
      </p>
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
