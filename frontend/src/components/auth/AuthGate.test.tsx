import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TooltipProvider } from '@/components/ui/tooltip'
import { AuthGate } from '@/components/auth/AuthGate'
import { ApiError } from '@/lib/api-error'
import { createQueryClient } from '@/lib/query-client'
import type { Account, Me } from '@/lib/types'

vi.mock('@/lib/auth-client', () => ({
  fetchMe: vi.fn(),
  fetchAccount: vi.fn(),
  logout: vi.fn(),
}))
vi.mock('@/lib/navigation', () => ({ navigateTo: vi.fn() }))

import { fetchMe, fetchAccount, logout } from '@/lib/auth-client'
import { navigateTo } from '@/lib/navigation'

const user = { name: 'Nadav', email: 'n@example.com' }
const signedIn: Me = { status: 'signed-in', user }
const account: Account = { displayCurrency: 'ILS' }

function renderGate(child = <p>the app</p>) {
  const client = createQueryClient()
  render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <AuthGate>{child}</AuthGate>
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return client
}

/** A stand-in for the dashboard: one query whose failure the gate must react to. */
function ChildWithQuery({ fail }: { fail: () => Promise<unknown> }) {
  useQuery({ queryKey: ['child'], queryFn: fail })
  return <p>the app</p>
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('AuthGate', () => {
  it('shows the neutral skeleton first, never dashboard rows', () => {
    vi.mocked(fetchMe).mockReturnValue(new Promise(() => {}))
    renderGate()
    expect(screen.getByLabelText('Loading')).toBeInTheDocument()
    expect(screen.queryByText('the app')).not.toBeInTheDocument()
  })

  it('anonymous → the sign-in screen', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ status: 'anonymous' })
    renderGate()
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByText('the app')).not.toBeInTheDocument()
    expect(fetchAccount).not.toHaveBeenCalled()
  })

  it('signed in + account 403 → the not-admitted screen, children never mount', async () => {
    vi.mocked(fetchMe).mockResolvedValue(signedIn)
    vi.mocked(fetchAccount).mockRejectedValue(new ApiError(403, 'Forbidden'))
    renderGate()
    expect(await screen.findByText(/isn't set up for PriceHunt yet/)).toBeInTheDocument()
    expect(screen.queryByText('the app')).not.toBeInTheDocument()
  })

  it('signed in + account ok → the shell around the children, with the currency', async () => {
    vi.mocked(fetchMe).mockResolvedValue(signedIn)
    vi.mocked(fetchAccount).mockResolvedValue(account)
    renderGate()
    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(screen.getByText('Nadav')).toBeInTheDocument()
    expect(screen.getByText('Prices in ILS')).toBeInTheDocument()
  })

  it('a non-403 account failure keeps the gate closed with Retry — admission is unknown, not granted', async () => {
    vi.mocked(fetchMe).mockResolvedValue(signedIn)
    vi.mocked(fetchAccount).mockRejectedValueOnce(new ApiError(502, 'Bad Gateway')).mockResolvedValue(account)
    renderGate()
    expect(await screen.findByText("Couldn't load your account.")).toBeInTheDocument()
    expect(screen.queryByText('the app')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(fetchMe).toHaveBeenCalledTimes(1)
  })

  it('offers Sign out on the account error, since a 400 from a stored currency never clears by retrying', async () => {
    vi.mocked(fetchMe).mockResolvedValue(signedIn)
    vi.mocked(fetchAccount).mockRejectedValue(new ApiError(400, 'Bad Request'))
    vi.mocked(logout).mockResolvedValue('https://tenant/oidc/logout')
    renderGate()
    expect(await screen.findByText("Couldn't load your account.")).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(navigateTo).toHaveBeenCalledWith('https://tenant/oidc/logout'))
  })

  it('a failed sign-out from the account error says so instead of appearing to do nothing', async () => {
    vi.mocked(fetchMe).mockResolvedValue(signedIn)
    vi.mocked(fetchAccount).mockRejectedValue(new ApiError(502, 'Bad Gateway'))
    vi.mocked(logout).mockRejectedValue(new Error('BFF unreachable'))
    renderGate()
    await screen.findByText("Couldn't load your account.")

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText("Couldn't sign out — try again.")).toBeInTheDocument()
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('BFF unreachable → auth-neutral error with Retry', async () => {
    vi.mocked(fetchMe).mockRejectedValueOnce(new ApiError(502, 'Bad Gateway')).mockResolvedValue({ status: 'anonymous' })
    renderGate()
    expect(await screen.findByText("Couldn't reach the sign-in service.")).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
  })

  it("a child query's 401 flips the gate to sign-in and unmounts the children (production rule)", async () => {
    vi.mocked(fetchMe).mockResolvedValue(signedIn)
    vi.mocked(fetchAccount).mockResolvedValue(account)
    let rejectChild: (e: unknown) => void = () => {}
    const fail = () => new Promise((_, reject) => (rejectChild = reject))
    renderGate(<ChildWithQuery fail={fail} />)
    expect(await screen.findByText('the app')).toBeInTheDocument()

    rejectChild(new ApiError(401, 'Unauthorized'))

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('the app')).not.toBeInTheDocument())
  })
})
