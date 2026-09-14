import { render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { TooltipProvider } from '@/components/ui/tooltip'
import { createQueryClient } from '@/lib/query-client'
import App from './App'

// The dashboard's own behaviour is covered in Dashboard.test.tsx and the
// gate's in AuthGate.test.tsx; here we only care that App composes them — a
// signed-in, admitted session whose dashboard fetch never resolves (the
// skeleton state is enough to prove the mount).
vi.mock('@/lib/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api-client')>()),
  fetchDashboard: vi.fn(() => new Promise(() => {})),
  fetchListings: vi.fn(() => new Promise(() => {})),
}))
vi.mock('@/lib/auth-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/auth-client')>()),
  fetchMe: vi.fn(() =>
    Promise.resolve({
      status: 'signed-in',
      user: { name: 'Nadav', email: 'n@example.com' },
    }),
  ),
  fetchAccount: vi.fn(() => Promise.resolve({ displayCurrency: 'ILS' })),
}))

describe('App', () => {
  it('mounts the dashboard inside the shell for a signed-in session (#157, #248)', async () => {
    // Vitest runs without VITE_USE_MOCK, i.e. exactly the live configuration.
    render(
      <QueryClientProvider client={createQueryClient()}>
        <TooltipProvider>
          <App />
        </TooltipProvider>
      </QueryClientProvider>,
    )

    // findBy: the gate's first render is the skeleton.
    expect(await screen.findByRole('button', { name: '+ Track a product' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    expect(screen.queryByText(/ships behind a demo flag/i)).not.toBeInTheDocument()
  })
})
