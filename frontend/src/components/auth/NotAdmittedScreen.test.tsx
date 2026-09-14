import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NotAdmittedScreen } from '@/components/auth/NotAdmittedScreen'
import { createQueryClient } from '@/lib/query-client'

vi.mock('@/lib/auth-client', () => ({ logout: vi.fn() }))
vi.mock('@/lib/navigation', () => ({ navigateTo: vi.fn() }))

import { logout } from '@/lib/auth-client'
import { navigateTo } from '@/lib/navigation'

function renderScreen() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <NotAdmittedScreen />
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('NotAdmittedScreen', () => {
  it('shows the invite copy, no Retry, and signs out to the URL the BFF returns', async () => {
    vi.mocked(logout).mockResolvedValue('https://tenant/oidc/logout')
    renderScreen()

    expect(screen.getByText(/isn't set up for PriceHunt yet/)).toBeInTheDocument()
    expect(screen.getByText(/Ask for an invite/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(navigateTo).toHaveBeenCalledWith('https://tenant/oidc/logout'))
  })

  it('a failed sign-out shows the inline error and stays on the screen', async () => {
    vi.mocked(logout).mockRejectedValue(new Error('down'))
    renderScreen()

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't sign out")
    expect(navigateTo).not.toHaveBeenCalled()
  })
})
