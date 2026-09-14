/**
 * The BFF's own routes (#248) — session, not data. The data routes live in
 * `api-client.ts`; both go through the same `request` helper.
 *
 * The one contract worth stating: a backend 401 never reaches the browser
 * (the BFF turns it into a 502), so a 401 on ANY `/bff/*` call means "no
 * session" — the single signal that flips the app to signed-out.
 */
import { API_BASE, request } from '@/lib/api-client'
import { isApiError } from '@/lib/api-error'
import type { Account, Me, User } from '@/lib/types'

/**
 * Mock mode (#157 pattern): `import.meta.env.DEV && VITE_USE_MOCK` signs you
 * in as a fixture so offline UI work still renders the dashboard. The
 * fixtures are inline constants behind the literal DEV condition — no new
 * import for the bundle sentinel gate to catch, and Rollup drops the branch.
 */
const MOCK_USER: User = { name: 'Demo User', email: 'demo@pricehunt.invalid' }

/** `GET /bff/me`: who is signed in, or that nobody is (an expected state, not an error). */
export async function fetchMe(): Promise<Me> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    return { status: 'signed-in', user: MOCK_USER }
  }
  try {
    const user = await request<User>('/bff/me')
    return { status: 'signed-in', user }
  } catch (error) {
    if (isApiError(error) && error.status === 401) return { status: 'anonymous' }
    throw error
  }
}

/** `GET /bff/api/me`: the backend's view of the account — the effective display currency. */
export async function fetchAccount(): Promise<Account> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    return { displayCurrency: 'ILS' }
  }
  return request<Account>(`${API_BASE}/me`)
}

/**
 * `POST /bff/logout` (CSRF-protected): ends the BFF session and returns the
 * Auth0 end-session URL to navigate to. A 401 resolves to `/`: the session
 * is already gone (expired, or a previous logout whose response was lost),
 * and reloading `/` lands on the sign-in screen — which is what the click
 * meant.
 */
export async function logout(): Promise<string> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    // Reloads into the fixture session rather than signing out: mock mode has no sign-in flow to
    // come back through, and there is no BFF running to post to.
    return '/'
  }
  try {
    const { logoutUrl } = await request<{ logoutUrl: string }>('/bff/logout', { method: 'POST' })
    return logoutUrl
  } catch (error) {
    if (isApiError(error) && error.status === 401) return '/'
    throw error
  }
}
