import { useSyncExternalStore } from 'react'

/**
 * Single shared ~60s ticker for relative "checked Nh ago" freshness (#144).
 *
 * ONE interval for the whole app (not one per row), started when the first
 * subscriber mounts and cleared when the last unmounts. Consumed ONLY inside
 * expanded listing panels — collapsed rows never subscribe, so they don't
 * re-render every minute. A `visibilitychange` refresh makes a
 * background-throttled tab show fresh values the moment it's refocused.
 */
const TICK_MS = 60_000

let now = Date.now()
const listeners = new Set<() => void>()
let intervalId: number | null = null

function tick(): void {
  now = Date.now()
  for (const listener of [...listeners]) listener()
}

function onVisibilityChange(): void {
  if (!document.hidden) tick()
}

function subscribe(listener: () => void): () => void {
  if (listeners.size === 0) {
    now = Date.now() // fresh baseline for the first subscriber
    intervalId = window.setInterval(tick, TICK_MS)
    document.addEventListener('visibilitychange', onVisibilityChange)
  }
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0 && intervalId !== null) {
      window.clearInterval(intervalId)
      intervalId = null
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }
}

export function useNow(): number {
  return useSyncExternalStore(subscribe, () => now)
}
