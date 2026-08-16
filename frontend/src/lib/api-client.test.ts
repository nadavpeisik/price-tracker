import { describe, expect, it } from 'vitest'
import { toBackendParams } from '@/lib/api-client'
import type { DashboardQuery } from '@/lib/types'

/**
 * The adapter owns the UI↔backend serialization (#144): repeated shops,
 * trimmed search, param names. Pagination is 1-based end to end (#146), so
 * `page` passes through untouched — the absence of arithmetic is the point.
 */
describe('toBackendParams', () => {
  const base: DashboardQuery = { sort: 'biggest7dDrop', page: 1, size: 20 }

  it('sends the UI page unchanged — pagination is 1-based on both sides', () => {
    expect(toBackendParams({ ...base, page: 1 }).get('page')).toBe('1')
    expect(toBackendParams({ ...base, page: 3 }).get('page')).toBe('3')
  })

  it('serializes shops as repeated params', () => {
    const params = toBackendParams({ ...base, shops: ['KSP', 'Ivory'] })
    expect(params.getAll('shops')).toEqual(['KSP', 'Ivory'])
  })

  it('omits empty search and trims whitespace', () => {
    expect(toBackendParams(base).has('search')).toBe(false)
    expect(toBackendParams({ ...base, search: '   ' }).has('search')).toBe(false)
    expect(toBackendParams({ ...base, search: ' sony ' }).get('search')).toBe('sony')
  })

  it('carries sort and size through', () => {
    const params = toBackendParams({ ...base, sort: 'lowestCurrentPrice', size: 50 })
    expect(params.get('sort')).toBe('lowestCurrentPrice')
    expect(params.get('size')).toBe('50')
  })
})
