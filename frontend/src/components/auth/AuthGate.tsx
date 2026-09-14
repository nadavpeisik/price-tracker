import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { AppShell } from '@/components/auth/AppShell'
import { BrandMark } from '@/components/auth/BrandMark'
import { NotAdmittedScreen } from '@/components/auth/NotAdmittedScreen'
import { SignInScreen } from '@/components/auth/SignInScreen'
import { useSignOut } from '@/hooks/use-sign-out'
import { isApiError } from '@/lib/api-error'
import { meQueryOptions, accountQueryOptions } from '@/lib/queries'

/**
 * Neutral placeholder while the session is unknown: brand header + one
 * centered card. NOT dashboard rows — that would flash a table at a visitor
 * about to see the sign-in screen.
 */
function GateSkeleton() {
  return (
    <div className="mx-auto max-w-[1080px] px-5 pb-24 pt-6" aria-busy="true" aria-label="Loading">
      <header className="mb-6 flex items-center justify-between gap-4">
        <BrandMark />
        <Skeleton className="h-9 w-24 rounded-[10px]" />
      </header>
      <Skeleton className="mx-auto h-40 w-full max-w-xl rounded-2xl" />
    </div>
  )
}

/**
 * The session or account could not be established: auth-neutral copy, never the dashboard's error
 * state. Sign out sits next to Retry because the shell is not mounted here, and not every failure
 * clears by retrying — a stored display currency the backend rejects 400s on every attempt — so
 * without it the only way out of this screen is the address bar.
 */
function GateError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { signOut, isPending, isError } = useSignOut()
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-6 px-5 py-16 text-center">
      <BrandMark />
      <p role="alert" className="text-sm text-ink-muted">
        {message}
      </p>
      <div className="flex items-center gap-3">
        <Button variant="outline" onClick={onRetry}>
          Retry
        </Button>
        <Button variant="ghost" onClick={signOut} disabled={isPending}>
          Sign out
        </Button>
      </div>
      {/* Likelier here than anywhere else: one of the two reasons this screen shows is an
          unreachable BFF, which is also what makes the logout POST fail. */}
      {isError && (
        <p role="alert" className="text-sm text-ink-muted">
          Couldn't sign out — try again.
        </p>
      )}
    </main>
  )
}

/**
 * The one auth decision in the app (#248): what to render for the session
 * `/bff/me` reports. Not a router — there is one screen — but the shape is
 * exactly react-router's `RequireAuth` layout route, so when #160 adds a
 * second path `{children}` becomes `<Outlet/>` and nothing else moves.
 *
 * A 401 anywhere flips `['me']` to anonymous through the QueryCache rule in
 * `query-client.ts`, which unmounts everything below.
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const me = useQuery(meQueryOptions())
  const signedIn = me.data?.status === 'signed-in'
  const account = useQuery(accountQueryOptions(signedIn))

  if (me.isPending) return <GateSkeleton />
  if (me.isError) return <GateError message="Couldn't reach the sign-in service." onRetry={() => void me.refetch()} />
  if (me.data.status === 'anonymous') return <SignInScreen />

  // The account answer IS the admission proof, so the gate opens only on a definite yes: 403 is the
  // definite no, anything else (backend down, 502 from the BFF) is "unknown" and stays closed with
  // Retry. Opening on an unknown would strand an uninvited identity on the dashboard's generic error,
  // since the account query never refetches on its own.
  if (account.isError) {
    if (isApiError(account.error) && account.error.status === 403) return <NotAdmittedScreen />
    return <GateError message="Couldn't load your account." onRetry={() => void account.refetch()} />
  }
  if (account.isPending) return <GateSkeleton />

  return (
    <AppShell user={me.data.user} displayCurrency={account.data.displayCurrency}>
      {children}
    </AppShell>
  )
}
