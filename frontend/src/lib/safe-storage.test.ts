import { afterEach, describe, expect, it, vi } from 'vitest'
import { safeStorage } from '@/lib/safe-storage'

describe('safeStorage degrade path', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    safeStorage.remove('t')
  })

  it('reads and writes through localStorage when available', () => {
    safeStorage.set('t', 'value')
    expect(localStorage.getItem('t')).toBe('value')
    expect(safeStorage.get('t')).toBe('value')
  })

  it('falls back to in-memory storage when setItem throws (Safari private mode)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError')
    })
    expect(() => safeStorage.set('t', 'memory-value')).not.toThrow()
    // The value must still round-trip within the session.
    expect(safeStorage.get('t')).toBe('memory-value')
  })

  it('survives reads throwing too (locked-down iframe)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError')
    })
    expect(() => safeStorage.get('t')).not.toThrow()
    expect(safeStorage.get('missing')).toBeNull()
  })
})
