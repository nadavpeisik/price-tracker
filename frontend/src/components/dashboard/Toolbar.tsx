import { useEffect, useState } from 'react'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { shopColorStyle } from '@/lib/shop-colors'
import type { DashboardSort } from '@/lib/types'
import type { DashboardUrlState } from '@/hooks/use-dashboard-url-state'

/**
 * Search / shop-filter / sort toolbar (#144). All state lives in the URL
 * (useDashboardUrlState); this component renders it and pushes edits.
 *
 * - The search input is LOCAL DRAFT state (instant typing); only the
 *   COMMITTED value — debounced ~300ms — hits the URL and triggers the
 *   backend round-trip. `dir="auto"` aligns Hebrew queries correctly.
 * - Shop chips render from the GLOBAL facets (server-side filtering means
 *   the current page can't enumerate every shop) as a Radix ToggleGroup —
 *   one tab stop, arrow-key nav, pressed state announced, multi-select.
 */

export const SEARCH_DEBOUNCE_MS = 300

const SORT_LABELS: Record<DashboardSort, string> = {
  biggest7dDrop: 'Biggest drop',
  lowestCurrentPrice: 'Lowest price',
  name: 'Name (A–Z)',
}

interface ToolbarProps {
  state: DashboardUrlState
  update: (patch: Partial<DashboardUrlState>) => void
  /** Global shop facets; undefined while the first response is loading. */
  shops: string[] | undefined
}

export function Toolbar({ state, update, shops }: ToolbarProps) {
  const [draft, setDraft] = useState(state.search)

  // External URL changes (back/forward, clear-filters) re-seed the draft —
  // the guarded adjust-state-during-render pattern (no effect, no extra
  // paint). After a normal debounce commit, seeded === state.search → no-op.
  const [seededFrom, setSeededFrom] = useState(state.search)
  if (seededFrom !== state.search) {
    setSeededFrom(state.search)
    setDraft(state.search)
  }

  // Debounce the COMMITTED query value; the input itself is never debounced.
  useEffect(() => {
    if (draft === state.search) return
    const timer = window.setTimeout(() => update({ search: draft }), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [draft, state.search, update])

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2.5">
      <div className="relative min-w-[180px] flex-[1_1_220px]">
        <Search
          className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-ink-faint"
          aria-hidden="true"
        />
        <Input
          type="search"
          dir="auto"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="Search products…"
          aria-label="Search products"
          className="h-10 rounded-[11px] border-line-strong bg-surface ps-9"
        />
      </div>

      {shops === undefined ? (
        <div className="flex gap-1.5" aria-hidden="true">
          <Skeleton className="h-[34px] w-20 rounded-full" />
          <Skeleton className="h-[34px] w-16 rounded-full" />
          <Skeleton className="h-[34px] w-16 rounded-full" />
        </div>
      ) : (
        <ToggleGroup
          type="multiple"
          value={state.shops}
          onValueChange={(value: string[]) => update({ shops: value })}
          aria-label="Filter by shop"
          spacing={1}
          className="flex-wrap"
        >
          {shops.map((shop) => (
            <ToggleGroupItem
              key={shop}
              value={shop}
              style={shopColorStyle(shop)}
              className="shop-color h-[34px] rounded-full border-[1.5px] border-line-strong bg-surface px-3 text-[13px] font-semibold text-ink-muted data-[state=on]:border-current data-[state=on]:bg-(--sc-bg) data-[state=on]:text-(--sc-text)"
            >
              <span className="size-2 rounded-full bg-(--sc-dot)" aria-hidden="true" />
              <bdi>{shop}</bdi>
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      )}

      <Select value={state.sort} onValueChange={(value) => update({ sort: value as DashboardSort })}>
        <SelectTrigger
          aria-label="Sort products"
          className="h-10 w-[180px] rounded-[11px] border-line-strong bg-surface text-[13.5px] font-medium"
        >
          <SelectValue>{`Sort: ${SORT_LABELS[state.sort]}`}</SelectValue>
        </SelectTrigger>
        <SelectContent>
          {(Object.keys(SORT_LABELS) as DashboardSort[]).map((key) => (
            <SelectItem key={key} value={key}>
              {SORT_LABELS[key]}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
