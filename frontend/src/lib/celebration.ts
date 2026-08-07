/**
 * Live price-drop celebration bookkeeping (#144) — pure logic, unit-tested.
 *
 * Rules:
 * - Fire only for a LIVE drop observed during the session: an incoming price
 *   lower than the previous snapshot for the SAME product id.
 * - Suppress the first successful load entirely (30 pre-existing drops must
 *   not set off 30 coin bursts on initial paint).
 * - Each real drop celebrates exactly once (`lastCelebratedPrice` keyed by
 *   product id) — a row remounting on pagination/filter never re-fires.
 */
import type { TrackedProduct } from '@/lib/types'

export interface CelebrationState {
  /** Last seen price per product id (across all queries this session). */
  snapshot: Map<number, number>
  /** Price at which each product last celebrated. */
  lastCelebrated: Map<number, number>
  seenFirstLoad: boolean
}

export function createCelebrationState(): CelebrationState {
  return { snapshot: new Map(), lastCelebrated: new Map(), seenFirstLoad: false }
}

/**
 * Record incoming items and return the product ids that should celebrate
 * now. Mutates `state` (snapshot updates happen for every seen item, even
 * when nothing celebrates).
 */
export function collectCelebrations(state: CelebrationState, items: TrackedProduct[]): number[] {
  const celebrate: number[] = []
  const firstLoad = !state.seenFirstLoad
  for (const item of items) {
    if (item.bestPriceConverted === null) {
      state.snapshot.delete(item.id)
      continue
    }
    const price = Number(item.bestPriceConverted)
    if (!Number.isFinite(price)) continue
    const previous = state.snapshot.get(item.id)
    state.snapshot.set(item.id, price)
    if (firstLoad || previous === undefined) continue
    if (price < previous && state.lastCelebrated.get(item.id) !== price) {
      state.lastCelebrated.set(item.id, price)
      celebrate.push(item.id)
    }
  }
  state.seenFirstLoad = true
  return celebrate
}
