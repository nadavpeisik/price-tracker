import { useSyncExternalStore } from 'react'

/**
 * Centralized `prefers-reduced-motion` state (#144) — reacts to the user
 * toggling the OS preference AT RUNTIME, not just the value at initial load.
 * Feeds the Motion config and the count-up/stagger/particle guards.
 */
const QUERY = '(prefers-reduced-motion: reduce)'

function subscribe(listener: () => void): () => void {
  const mq = window.matchMedia(QUERY)
  mq.addEventListener('change', listener)
  return () => mq.removeEventListener('change', listener)
}

function getSnapshot(): boolean {
  return window.matchMedia(QUERY).matches
}

export function useReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot)
}
