import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SignInScreen } from '@/components/auth/SignInScreen'
import * as urlState from '@/lib/url-state'

beforeEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('SignInScreen', () => {
  it('the Sign in link is a top-level navigation whose href follows the remember-me checkbox', async () => {
    render(<SignInScreen />)
    const link = screen.getByRole('link', { name: 'Sign in' })
    expect(link).toHaveAttribute('href', '/bff/login')

    await userEvent.click(screen.getByRole('checkbox', { name: 'Keep me signed in for 90 days' }))
    expect(link).toHaveAttribute('href', '/bff/login?remember=true')

    await userEvent.click(screen.getByRole('checkbox', { name: 'Keep me signed in for 90 days' }))
    expect(link).toHaveAttribute('href', '/bff/login')
  })

  it('the checkbox styles its checked state with the attribute Radix actually emits', async () => {
    // The vendored shadcn component shipped `data-checked:` variants, which match nothing: Radix
    // renders `data-state="checked"`. Ticking the box then changed only the tick icon. Re-vendoring
    // the component would reintroduce it, so both halves of the coupling are pinned here.
    render(<SignInScreen />)
    const checkbox = screen.getByRole('checkbox', { name: 'Keep me signed in for 90 days' })

    await userEvent.click(checkbox)

    expect(checkbox).toHaveAttribute('data-state', 'checked')
    expect(checkbox.className).toContain('data-[state=checked]:bg-primary')
    expect(checkbox.className).not.toContain('data-checked:')
  })

  it('renders the ?loginError banner once and strips the param with one replace', () => {
    window.history.replaceState(null, '', '/?loginError=access_denied')
    const spy = vi.spyOn(urlState, 'setLocationSearch')

    render(<SignInScreen />)

    expect(screen.getByRole('alert')).toHaveTextContent("Sign-in didn't complete (access_denied)")
    expect(spy).toHaveBeenCalledTimes(1)
    expect(spy.mock.calls[0][0].toString()).toBe('')
    expect(spy.mock.calls[0][1]).toBe('replace')
    expect(window.location.search).toBe('')
  })

  it('an out-of-contract code renders as "unknown", never echoed', () => {
    window.history.replaceState(null, '', '/?loginError=' + encodeURIComponent('<img src=x>'))
    render(<SignInScreen />)
    expect(screen.getByRole('alert')).toHaveTextContent("Sign-in didn't complete (unknown)")
    expect(screen.getByRole('alert')).not.toHaveTextContent('img')
  })

  it('no banner and no URL write without the param', () => {
    const spy = vi.spyOn(urlState, 'setLocationSearch')
    render(<SignInScreen />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(spy).not.toHaveBeenCalled()
  })
})
