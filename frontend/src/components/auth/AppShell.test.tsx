import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from '@/components/auth/AppShell'
import { createQueryClient } from '@/lib/query-client'
import type { User } from '@/lib/types'
import { resetMatchMedia } from '@/test/setup'

vi.mock('@/lib/auth-client', () => ({ logout: vi.fn() }))
vi.mock('@/lib/navigation', () => ({ navigateTo: vi.fn() }))

import { logout } from '@/lib/auth-client'
import { navigateTo } from '@/lib/navigation'

const user: User = { name: 'Nadav', email: 'n@example.com' }

function renderShell({ user: who = user, displayCurrency = 'ILS' }: { user?: User; displayCurrency?: string } = {}) {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <AppShell user={who} displayCurrency={displayCurrency}>
        <p>page content</p>
      </AppShell>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  resetMatchMedia()
  localStorage.clear()
  document.documentElement.classList.remove('dark')
})

describe('AppShell', () => {
  it('renders the brand, the account cluster and the children', () => {
    renderShell()
    expect(screen.getByText('PriceHunt')).toBeInTheDocument()
    expect(screen.getByText('Nadav')).toBeInTheDocument()
    expect(screen.getByText('Prices in ILS')).toBeInTheDocument()
    expect(screen.getByText('page content')).toBeInTheDocument()
  })

  it('falls back to the email when the ID token has no name', () => {
    renderShell({ user: { ...user, name: null }, displayCurrency: 'USD' })
    expect(screen.getByText('n@example.com')).toBeInTheDocument()
    expect(screen.getByText('Prices in USD')).toBeInTheDocument()
  })

  it('the theme toggle flips the dark class', async () => {
    renderShell()
    await userEvent.click(screen.getByRole('button', { name: 'Toggle light and dark theme' }))
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    await userEvent.click(screen.getByRole('button', { name: 'Toggle light and dark theme' }))
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('Sign out posts the logout and navigates to the returned URL', async () => {
    vi.mocked(logout).mockResolvedValue('https://tenant/oidc/logout?client_id=x')
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(navigateTo).toHaveBeenCalledWith('https://tenant/oidc/logout?client_id=x'))
  })

  it('a failed sign-out shows the inline error and keeps the page', async () => {
    vi.mocked(logout).mockRejectedValue(new Error('down'))
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't sign out")
    expect(screen.getByText('page content')).toBeInTheDocument()
    expect(navigateTo).not.toHaveBeenCalled()
  })
})
