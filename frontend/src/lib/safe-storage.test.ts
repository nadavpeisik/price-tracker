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

  it('prefers the in-memory value over a STALE localStorage value after a failed write', () => {
    // An earlier successful write left 'old' in localStorage...
    safeStorage.set('t', 'old')
    expect(localStorage.getItem('t')).toBe('old')
    // ...then a later write fails (quota / private mode) and falls to memory.
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError')
    })
    safeStorage.set('t', 'new')
    // get() must return the latest value, not the stale localStorage one.
    expect(safeStorage.get('t')).toBe('new')
  })

  it('clears the in-memory override once a later write succeeds again', () => {
    safeStorage.set('t', 'old')
    // One failing write forces the value into memory...
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw new DOMException('QuotaExceededError')
    })
    safeStorage.set('t', 'from-memory')
    // ...then a successful write must heal back to localStorage authority.
    safeStorage.set('t', 'persisted')
    expect(localStorage.getItem('t')).toBe('persisted')
    expect(safeStorage.get('t')).toBe('persisted')
  })
})
