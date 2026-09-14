import { AuthGate } from '@/components/auth/AuthGate'
import { Dashboard } from '@/components/dashboard/Dashboard'

/**
 * The app IS the dashboard (#157), behind the session gate (#248): the gate
 * renders sign-in / not-admitted / the shell around it depending on what the
 * BFF says about the session. Whether the dashboard reads live data or the
 * typed mock is decided inside `api-client.ts` / `auth-client.ts` (DEV +
 * VITE_USE_MOCK), not here.
 */
function App() {
  return (
    <AuthGate>
      <Dashboard />
    </AuthGate>
  )
}

export default App
