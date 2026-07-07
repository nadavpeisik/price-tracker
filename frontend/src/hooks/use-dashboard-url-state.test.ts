import { beforeEach, describe, expect, it } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  DEFAULT_SORT,
  parseDashboardSearch,
  serializeDashboardState,
  useDashboardUrlState,
} from '@/hooks/use-dashboard-url-state'

describe('parseDashboardSearch (read on load)', () => {
  it('reads q, repeated shop params, sort, and page', () => {
    const state = parseDashboardSearch('?q=sony&shop=KSP&shop=Ivory&sort=name&page=3')
    expect(state).toEqual({ search: 'sony', shops: ['KSP', 'Ivory'], sort: 'name', page: 3 })
  })

  it('applies defaults for missing params', () => {
    expect(parseDashboardSearch('')).toEqual({
      search: '',
      shops: [],
      sort: DEFAULT_SORT,
      page: 1,
    })
  })

  it('falls back to the primary sort for an unknown sort value', () => {
    expect(parseDashboardSearch('?sort=hackery').sort).toBe(DEFAULT_SORT)
  })

  it('sanitizes bad page values to 1', () => {
    expect(parseDashboardSearch('?page=0').page).toBe(1)
    expect(parseDashboardSearch('?page=-4').page).toBe(1)
    expect(parseDashboardSearch('?page=NaN').page).toBe(1)
    expect(parseDashboardSearch('?page=2.5').page).toBe(1)
  })
})

describe('serializeDashboardState', () => {
  it('omits defaults so URLs stay clean', () => {
    expect(
      serializeDashboardState({ search: '', shops: [], sort: DEFAULT_SORT, page: 1 }).toString(),
    ).toBe('')
  })

  it('round-trips through parse', () => {
    const state = { search: 'sony', shops: ['KSP', 'Ivory'], sort: 'name' as const, page: 2 }
    expect(parseDashboardSearch(`?${serializeDashboardState(state)}`)).toEqual(state)
  })
})

describe('useDashboardUrlState (write on change)', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('writes state changes to the URL via replaceState and re-renders', () => {
    const { result } = renderHook(() => useDashboardUrlState())
    act(() => result.current.update({ search: 'sony', shops: ['KSP'] }))
    expect(window.location.search).toBe('?q=sony&shop=KSP')
    expect(result.current.state.search).toBe('sony')
    expect(result.current.state.shops).toEqual(['KSP'])
  })

  it('resets to page 1 when search/filter/sort change', () => {
    window.history.replaceState(null, '', '/?page=5')
    const { result } = renderHook(() => useDashboardUrlState())
    expect(result.current.state.page).toBe(5)
    act(() => result.current.update({ search: 'x' }))
    expect(result.current.state.page).toBe(1)
  })

  it('keeps the page when only the page changes (explicit pagination)', () => {
    const { result } = renderHook(() => useDashboardUrlState())
    act(() => result.current.update({ page: 4 }))
    expect(result.current.state.page).toBe(4)
    expect(window.location.search).toBe('?page=4')
  })

  it('reflects back/forward navigation (popstate)', () => {
    const { result } = renderHook(() => useDashboardUrlState())
    act(() => {
      window.history.pushState(null, '', '/?q=older')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(result.current.state.search).toBe('older')
  })
})
