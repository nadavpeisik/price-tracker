import { Dashboard } from '@/components/dashboard/Dashboard'

/**
 * The app IS the dashboard (#157). Whether it reads live data or the typed
 * mock is decided inside `api-client.ts` (DEV + VITE_USE_MOCK), not here —
 * the prod gate that used to render a placeholder retired with the mock
 * default once #145/#146 landed.
 */
function App() {
  return <Dashboard />
}

export default App
