// Global test setup — registers jest-dom matchers (toBeInTheDocument, …) on
// Vitest's expect, and fills jsdom gaps our components rely on.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// With Vitest globals disabled, RTL cannot auto-register its afterEach DOM
// cleanup — do it explicitly or every test leaks its DOM into the next.
afterEach(() => cleanup())

/**
 * jsdom has no matchMedia — provide a controllable stub. Tests flip
 * `setMatchMediaMatches()` to simulate prefers-reduced-motion /
 * prefers-color-scheme, and can fire registered listeners via
 * `dispatchMatchMediaChange()`.
 */
type MediaListener = (event: { matches: boolean; media: string }) => void

const mediaListeners = new Map<string, Set<MediaListener>>()
const mediaMatches = new Map<string, boolean>()

export function setMatchMediaMatches(query: string, matches: boolean): void {
  mediaMatches.set(query, matches)
}

export function dispatchMatchMediaChange(query: string, matches: boolean): void {
  mediaMatches.set(query, matches)
  for (const listener of mediaListeners.get(query) ?? []) {
    listener({ matches, media: query })
  }
}

export function resetMatchMedia(): void {
  mediaListeners.clear()
  mediaMatches.clear()
}

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: mediaMatches.get(query) ?? false,
    media: query,
    onchange: null,
    addEventListener: (_type: string, listener: MediaListener) => {
      let set = mediaListeners.get(query)
      if (!set) {
        set = new Set()
        mediaListeners.set(query, set)
      }
      set.add(listener)
    },
    removeEventListener: (_type: string, listener: MediaListener) => {
      mediaListeners.get(query)?.delete(listener)
    },
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

// Radix ToggleGroup/Select use pointer-capture + scrollIntoView APIs jsdom lacks.
window.HTMLElement.prototype.hasPointerCapture ??= () => false
window.HTMLElement.prototype.setPointerCapture ??= () => {}
window.HTMLElement.prototype.releasePointerCapture ??= () => {}
window.HTMLElement.prototype.scrollIntoView ??= () => {}
