/**
 * Minimal URL query-string store (#144) — the ONE place that reads and
 * writes `location.search`.
 *
 * Built on the useSyncExternalStore contract (no tearing under React 19
 * concurrent rendering). The subtle part this store exists for:
 * `history.pushState`/`replaceState` do NOT emit `popstate`, so a hook that
 * only listens for `popstate` misses its own writes — this store notifies
 * its subscribers synchronously after every programmatic write, and also
 * subscribes to `popstate` for back/forward navigation.
 */
type Listener = () => void

const listeners = new Set<Listener>()
let popstateAttached = false

function notify(): void {
  for (const listener of [...listeners]) listener()
}

export function subscribeToLocationSearch(listener: Listener): () => void {
  listeners.add(listener)
  if (!popstateAttached) {
    window.addEventListener('popstate', notify)
    popstateAttached = true
  }
  return () => {
    listeners.delete(listener)
  }
}

export function getLocationSearch(): string {
  return window.location.search
}

/**
 * Write the query string. 'replace' for filter/search edits (no history
 * entry per keystroke); 'push' is reserved for deliberate navigation.
 */
export function setLocationSearch(params: URLSearchParams, mode: 'replace' | 'push'): void {
  const qs = params.toString()
  const nextSearch = qs ? `?${qs}` : ''
  if (nextSearch === window.location.search) return
  const url = `${window.location.pathname}${nextSearch}${window.location.hash}`
  if (mode === 'push') {
    window.history.pushState(window.history.state, '', url)
  } else {
    window.history.replaceState(window.history.state, '', url)
  }
  notify()
}
