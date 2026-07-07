import { useEffect, useRef, useState } from 'react'

/**
 * Animated count-up for tile values / prices (#144). Eases to the target
 * with the mockup's cubic ease-out over ~800ms, starting from the value
 * currently on screen — so the initial reveal climbs from 0, but a later
 * change (e.g. a live refetch nudging a tile from 4 → 5) animates 4 → 5
 * instead of snapping back to 0 and re-climbing. `disabled` (reduced motion,
 * or a row past the stagger cap) returns the target directly.
 */
export function useCountUp(target: number, disabled: boolean, durationMs = 800): number {
  const [animated, setAnimated] = useState(0)
  // The value currently shown — the start point for the next animation.
  const displayedRef = useRef(disabled ? target : 0)

  useEffect(() => {
    if (disabled) {
      displayedRef.current = target
      // Keep `animated` in sync so that if reduced motion is turned OFF at
      // runtime, the next render doesn't briefly show the stale value before
      // rAF catches up. Deferred to a frame so it isn't a synchronous
      // setState in the effect body (hooks lint), and is harmless when we
      // stay disabled (the returned value is the target either way).
      const id = requestAnimationFrame(() => setAnimated(target))
      return () => cancelAnimationFrame(id)
    }
    const from = displayedRef.current
    const start = performance.now()
    let frame: number
    const tickFrame = (t: number) => {
      // Guard a zero/negative duration (would divide to Infinity/NaN).
      const p = durationMs <= 0 ? 1 : Math.min((t - start) / durationMs, 1)
      const eased = 1 - Math.pow(1 - p, 3)
      const value = from + (target - from) * eased
      displayedRef.current = value
      setAnimated(value)
      if (p < 1) frame = requestAnimationFrame(tickFrame)
    }
    frame = requestAnimationFrame(tickFrame)
    return () => cancelAnimationFrame(frame)
  }, [target, disabled, durationMs])

  return disabled ? target : animated
}
