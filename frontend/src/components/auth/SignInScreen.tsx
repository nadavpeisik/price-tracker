import { useEffect, useState } from 'react'
import { Checkbox } from '@/components/ui/checkbox'
import { buttonVariants } from '@/components/ui/button'
import { BrandMark } from '@/components/auth/BrandMark'
import { setLocationSearch } from '@/lib/url-state'
import { cn } from '@/lib/utils'

/**
 * The BFF redirects a failed callback to `/?loginError=<code>`; codes are
 * `[a-z_]{1,64}` by its contract. Re-checked here because `location.search`
 * is something any link can set, so the banner never echoes free text.
 */
const LOGIN_ERROR_CODE_PATTERN = /^[a-z_]{1,64}$/

function readLoginErrorCode(): string | null {
  const raw = new URLSearchParams(window.location.search).get('loginError')
  if (raw === null) return null
  return LOGIN_ERROR_CODE_PATTERN.test(raw) ? raw : 'unknown'
}

/**
 * Signed-out landing (#248). "Sign in" is an anchor to `/bff/login`: a
 * top-level navigation, deliberately not a fetch, because the OAuth redirect
 * dance has to happen in the address bar.
 */
export function SignInScreen() {
  const [keepSignedIn, setKeepSignedIn] = useState(false)
  // Captured ONCE, lazily: it survives StrictMode's dev-only mount/unmount/
  // remount, and the effect below strips it from the URL right after.
  const [loginErrorCode] = useState(readLoginErrorCode)

  useEffect(() => {
    if (loginErrorCode === null) return
    const params = new URLSearchParams(window.location.search)
    params.delete('loginError')
    setLocationSearch(params, 'replace')
  }, [loginErrorCode])

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-6 px-5 py-16 text-center">
      <BrandMark />
      <h1 className="sr-only">Sign in to PriceHunt</h1>
      <p className="text-sm text-ink-muted">Track prices across shops and get told when they drop.</p>

      {loginErrorCode !== null && (
        <p
          role="alert"
          className="w-full rounded-xl border border-line bg-surface-2 px-4 py-3 text-sm text-ink-muted"
        >
          Sign-in didn't complete (<code className="font-mono text-xs">{loginErrorCode}</code>). Try again.
        </p>
      )}

      <a
        href={keepSignedIn ? '/bff/login?remember=true' : '/bff/login'}
        className={cn(buttonVariants({ size: 'lg' }), 'w-full rounded-[10px] font-semibold')}
      >
        Sign in
      </a>

      <label className="flex items-center gap-2 text-sm text-ink-muted">
        <Checkbox checked={keepSignedIn} onCheckedChange={(value) => setKeepSignedIn(value === true)} />
        Keep me signed in for 90 days
      </label>
    </main>
  )
}
