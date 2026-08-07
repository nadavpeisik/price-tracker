import { useEffect, useState } from 'react'
import { safeStorage } from '@/lib/safe-storage'

/**
 * Theme state (#144): system-adaptive light/dark with a manual override that
 * wins in both directions.
 *
 * - The inline pre-paint script in index.html already applied the correct
 *   theme class before first paint (FOUC guard). This hook initializes
 *   SYNCHRONOUSLY from that DOM class — one source of truth, no re-derive,
 *   no second flicker.
 * - No stored override → the app follows the OS preference live.
 * - Toggling stores an explicit 'light' | 'dark' override; from then on the
 *   OS preference is ignored (until storage is cleared).
 *
 * Storage key must stay in sync with the pre-paint script in index.html.
 */
export const THEME_KEY = 'ph.theme'

export type Theme = 'light' | 'dark'

// Mirrors --bg in src/index.css and the pre-paint script in index.html.
const THEME_COLOR = { light: '#F1EDE7', dark: '#14120D' } as const

function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  // Keep the mobile browser-chrome color in sync with a manual override
  // (a prefers-color-scheme media meta wouldn't follow the override).
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[theme])
}

export function useTheme(): { theme: Theme; toggle: () => void } {
  // Lazy initializer: runs once on first render, reading the class the
  // pre-paint script set.
  const [theme, setTheme] = useState<Theme>(() =>
    document.documentElement.classList.contains('dark') ? 'dark' : 'light',
  )

  useEffect(() => {
    // Follow live OS theme changes while there is no explicit user override.
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if (safeStorage.get(THEME_KEY) !== null) return
      const next: Theme = mq.matches ? 'dark' : 'light'
      applyTheme(next)
      setTheme(next)
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const toggle = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    safeStorage.set(THEME_KEY, next)
    applyTheme(next)
    setTheme(next)
  }

  return { theme, toggle }
}
