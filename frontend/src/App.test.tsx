import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import App from './App'

describe('App', () => {
  it('renders the prod-gate placeholder when mock mode is off', () => {
    // Vitest runs without VITE_USE_MOCK → the dashboard must NOT mount
    // (mock/demo-only until #145/#146 land).
    render(<App />)
    expect(screen.getByRole('heading', { name: 'PriceHunt' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Track a product' })).not.toBeInTheDocument()
  })
})
