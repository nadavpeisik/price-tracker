/**
 * Delta-pill direction classification (#144). |delta| within the threshold
 * renders as flat (mockup semantics). Kept out of the component file so it
 * is importable from non-component code without breaking fast refresh.
 */
export const FLAT_THRESHOLD = 0.15

export function deltaDirection(delta: number): 'down' | 'up' | 'flat' {
  if (delta < -FLAT_THRESHOLD) return 'down'
  if (delta > FLAT_THRESHOLD) return 'up'
  return 'flat'
}
