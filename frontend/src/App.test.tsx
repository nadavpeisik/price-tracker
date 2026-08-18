import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { TooltipProvider } from '@/components/ui/tooltip'
import App from './App'

// The dashboard's own behaviour is covered in Dashboard.test.tsx; here we only
// care that App mounts it — so the API is stubbed to a never-resolving fetch
// (the skeleton state is enough to prove the mount).
vi.mock('@/lib/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api-client')>()),
  fetchDashboard: vi.fn(() => new Promise(() => {})),
  fetchListings: vi.fn(() => new Promise(() => {})),
}))

describe('App', () => {
  it('mounts the dashboard in a normal (non-mock) build — the prod-gate placeholder is gone (#157)', () => {
    // Vitest runs without VITE_USE_MOCK, i.e. exactly the live configuration.
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <TooltipProvider>
          <App />
        </TooltipProvider>
      </QueryClientProvider>,
    )

    expect(screen.getByRole('button', { name: '+ Track a product' })).toBeInTheDocument()
    expect(screen.queryByText(/ships behind a demo flag/i)).not.toBeInTheDocument()
  })
})
