import type { ReactNode } from 'react'
import { Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { BrandMark } from '@/components/auth/BrandMark'
import { useSignOut } from '@/hooks/use-sign-out'
import { useTheme } from '@/hooks/use-theme'
import type { User } from '@/lib/types'

interface AppShellProps {
  user: User
  /** The effective display currency from `/api/me`. */
  displayCurrency: string
  children: ReactNode
}

/**
 * The authenticated layout (#248): page container + chrome (brand, theme
 * toggle, account cluster). Moved out of `Dashboard.tsx` so the dashboard
 * keeps only page content and knows nothing about auth.
 */
export function AppShell({ user, displayCurrency, children }: AppShellProps) {
  const { theme, toggle } = useTheme()
  const { signOut, isPending, isError } = useSignOut()

  return (
    <div className="mx-auto max-w-[1080px] px-5 pb-24 pt-6">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-4">
        <BrandMark />
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex flex-col items-end text-right">
            {/* Auth0 always supplies the email; `name` is the nicer label when present. No further
                fallback chain (raised in three review rounds): an absent claim arrives as null, not as
                an empty string, and an account with neither claim cannot reach this screen. */}
            <span className="text-sm font-medium">{user.name ?? user.email}</span>
            <span className="text-xs text-ink-muted">Prices in {displayCurrency}</span>
          </div>
          <Button
            variant="outline"
            size="icon"
            onClick={toggle}
            aria-label="Toggle light and dark theme"
            className="size-9 rounded-[10px] border-line-strong bg-surface text-ink-muted"
          >
            {theme === 'dark' ? <Moon className="size-4" /> : <Sun className="size-4" />}
          </Button>
          <Button variant="outline" className="rounded-[10px]" onClick={signOut} disabled={isPending}>
            Sign out
          </Button>
        </div>
      </header>

      {isError && (
        <p role="alert" className="mb-4 text-sm text-ink-muted">
          Couldn't sign out — try again.
        </p>
      )}

      {children}
    </div>
  )
}
