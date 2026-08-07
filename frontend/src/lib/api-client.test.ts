import { describe, expect, it } from 'vitest'
import { toBackendParams } from '@/lib/api-client'
import type { DashboardQuery } from '@/lib/types'

/**
 * The adapter owns the UI↔backend translation (#144): 1-based UI page →
 * Spring's 0-based Pageable, repeated shops, trimmed search, param names.
 */
describe('toBackendParams', () => {
  const base: DashboardQuery = { sort: 'biggest7dDrop', page: 1, size: 20 }

  it('translates the 1-based UI page to a 0-based backend page', () => {
    expect(toBackendParams({ ...base, page: 1 }).get('page')).toBe('0')
    expect(toBackendParams({ ...base, page: 3 }).get('page')).toBe('2')
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
