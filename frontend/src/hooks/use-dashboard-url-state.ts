import { useCallback, useMemo } from 'react'
import { useSyncExternalStore } from 'react'
import {
  getLocationSearch,
  setLocationSearch,
  subscribeToLocationSearch,
} from '@/lib/url-state'
import { DASHBOARD_SORTS, type DashboardSort } from '@/lib/types'

/**
 * Dashboard toolbar state, synced to URL query params (#144) so refresh and
 * sharing preserve the view.
 *
 * Canonical PUBLIC URL contract (the API client adapts to the backend):
 *   ?q=<search>&shop=<name>&shop=<name>&sort=<key>&page=<1-based>
 * Defaults are omitted from the URL to keep it clean. Validation on load:
 * unknown sort → primary sort; bad page → 1. (`?shop=` values are validated
 * against facets only AFTER the response loads — the Dashboard owns that,
 * so a valid bookmarked shop is never discarded during loading.)
 */
export interface DashboardUrlState {
  search: string
  shops: string[]
  sort: DashboardSort
  page: number
}

export const DEFAULT_SORT: DashboardSort = 'biggest7dDrop'

export function parseDashboardSearch(searchString: string): DashboardUrlState {
  const params = new URLSearchParams(searchString)
  const sortRaw = params.get('sort')
  const sort = (DASHBOARD_SORTS as readonly string[]).includes(sortRaw ?? '')
    ? (sortRaw as DashboardSort)
    : DEFAULT_SORT
  const pageRaw = Number(params.get('page'))
  const page = Number.isInteger(pageRaw) && pageRaw >= 1 ? pageRaw : 1
  return {
    search: params.get('q') ?? '',
    shops: params.getAll('shop'),
    sort,
    page,
  }
}

export function serializeDashboardState(state: DashboardUrlState): URLSearchParams {
  const params = new URLSearchParams()
  if (state.search) params.set('q', state.search)
  for (const shop of state.shops) params.append('shop', shop)
  if (state.sort !== DEFAULT_SORT) params.set('sort', state.sort)
  if (state.page !== 1) params.set('page', String(state.page))
  return params
}

export function useDashboardUrlState(): {
  state: DashboardUrlState
  update: (patch: Partial<DashboardUrlState>) => void
} {
  const searchString = useSyncExternalStore(subscribeToLocationSearch, getLocationSearch)
  const state = useMemo(() => parseDashboardSearch(searchString), [searchString])

  const update = useCallback((patch: Partial<DashboardUrlState>) => {
    const current = parseDashboardSearch(getLocationSearch())
    const next: DashboardUrlState = { ...current, ...patch }
    // A search/filter/sort change resets to page 1 (a narrowed set must not
    // strand the user on an empty page 5) — unless the patch itself paginates.
    const changesQuery =
      ('search' in patch && patch.search !== current.search) ||
      ('shops' in patch && JSON.stringify(patch.shops) !== JSON.stringify(current.shops)) ||
      ('sort' in patch && patch.sort !== current.sort)
    if (changesQuery && !('page' in patch)) next.page = 1
    // replaceState for filter/search edits — no history entry per keystroke.
    setLocationSearch(serializeDashboardState(next), 'replace')
  }, [])

  return { state, update }
}
