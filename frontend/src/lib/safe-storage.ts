/**
 * Centralized safe wrapper around localStorage (#144).
 *
 * localStorage is not reliably available: Safari private mode lets reads
 * succeed but throws on writes; locked-down privacy settings and sandboxed
 * iframes throw on any access. Every persisted flag in the app (theme
 * override, first-run flag) goes through this single try/catch boundary and
 * degrades to an in-memory Map — values then survive the session but not a
 * reload, and nothing ever crashes. (sessionStorage is NOT a valid fallback:
 * it throws under the same conditions.)
 */
const memory = new Map<string, string>()

export const safeStorage = {
  get(key: string): string | null {
    try {
      const value = localStorage.getItem(key)
      if (value !== null) return value
    } catch {
      /* storage unavailable — fall through to memory */
    }
    // Also consulted when localStorage READS work but an earlier WRITE threw
    // (Safari private mode) and the value landed in memory.
    return memory.get(key) ?? null
  },

  set(key: string, value: string): void {
    try {
      localStorage.setItem(key, value)
      return
    } catch {
      /* storage unavailable or quota/private-mode write failure */
    }
    memory.set(key, value)
  },

  remove(key: string): void {
    try {
      localStorage.removeItem(key)
    } catch {
      /* ignore — memory cleanup below still runs */
    }
    memory.delete(key)
  },
}
