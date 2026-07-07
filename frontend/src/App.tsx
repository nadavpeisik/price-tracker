import { Dashboard } from '@/components/dashboard/Dashboard'
import { isMockMode } from '@/lib/api-client'

/**
 * Prod gate (#144): the dashboard is demo/mock-only until the backend
 * dashboard query (#146) + delta engine (#145) land — it must NOT be
 * mounted in a production app serving real users. Mock mode only exists in
 * DEV builds (vite.config.ts fails a production build with VITE_USE_MOCK),
 * so production renders the placeholder. Live wiring replaces this gate
 * with the real data adapter.
 */
function App() {
  if (isMockMode()) {
    return <Dashboard />
  }
  return (
    <main className="grid min-h-svh place-items-center px-6 text-center">
      <div>
        <h1 className="font-display text-2xl font-bold">PriceHunt</h1>
        <p className="mt-2 max-w-md text-sm text-ink-muted">
          The dashboard UI ships behind a demo flag until the backend dashboard endpoint lands
          (#145/#146). Run the dev server with <code className="font-num">VITE_USE_MOCK=true</code>{' '}
          to preview it with mock data.
        </p>
      </div>
    </main>
  )
}

export default App
