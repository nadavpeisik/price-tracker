import { useEffect, useRef } from 'react'
import { burstCurrency } from '@/lib/particles'
import { useReducedMotion } from '@/hooks/use-reduced-motion'

/**
 * First-run rocket intro (#144): launch → explosion → flying currency
 * symbols, once per browser. The PARENT owns the gating (persisted
 * first-run flag + query resolved success-and-empty); this component just
 * plays the sequence and reports completion. Click skips. Reduced motion
 * completes immediately.
 */
export function RocketIntro({ onDone }: { onDone: () => void }) {
  const reducedMotion = useReducedMotion()
  const rocketRef = useRef<HTMLSpanElement | null>(null)
  const flashRef = useRef<HTMLDivElement | null>(null)
  const doneRef = useRef(false)

  const finish = () => {
    if (doneRef.current) return
    doneRef.current = true
    onDone()
  }

  useEffect(() => {
    const rocket = rocketRef.current
    if (reducedMotion || !rocket || typeof rocket.animate !== 'function') {
      finish()
      return
    }

    rocket.animate(
      [
        { bottom: '-60px', transform: 'translateX(-50%) rotate(-6deg)' },
        { bottom: '40%', transform: 'translateX(-50%) rotate(4deg)', offset: 0.8 },
        { bottom: '52%', transform: 'translateX(-50%) rotate(0deg)' },
      ],
      { duration: 900, easing: 'cubic-bezier(.3,.1,.3,1)', fill: 'forwards' },
    )
    // Exhaust trail while ascending.
    const trail = window.setInterval(() => {
      const r = rocket.getBoundingClientRect()
      burstCurrency(r.left + r.width / 2, r.bottom - 8, { count: 1, spread: 26, size: 12 })
    }, 55)

    const explode = window.setTimeout(() => {
      window.clearInterval(trail)
      const r = rocket.getBoundingClientRect()
      rocket.style.opacity = '0'
      flashRef.current?.animate(
        [
          { transform: 'translate(-50%,-50%) scale(0)', opacity: 1 },
          { transform: 'translate(-50%,-50%) scale(9)', opacity: 0 },
        ],
        { duration: 520, easing: 'ease-out' },
      )
      burstCurrency(r.left + r.width / 2, r.top + r.height / 2, { count: 30, spread: 240, size: 22 })
    }, 900)

    const end = window.setTimeout(finish, 1520)

    return () => {
      window.clearInterval(trail)
      window.clearTimeout(explode)
      window.clearTimeout(end)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- play once on mount
  }, [])

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center overflow-hidden bg-background"
      onClick={finish}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') finish()
      }}
      role="button"
      tabIndex={0}
      aria-label="Skip intro animation"
    >
      <span
        ref={rocketRef}
        className="absolute bottom-[-60px] left-1/2 -translate-x-1/2 text-[62px] drop-shadow-lg"
        aria-hidden="true"
      >
        🚀
      </span>
      <div
        ref={flashRef}
        aria-hidden="true"
        className="absolute left-1/2 top-[42%] size-5 -translate-x-1/2 -translate-y-1/2 scale-0 rounded-full opacity-0 [background:radial-gradient(circle,#fff,var(--warn)_45%,transparent_70%)]"
      />
      <p className="absolute inset-x-0 bottom-6 text-center text-xs tracking-wide text-ink-faint">
        launching PriceHunt… <span className="opacity-70">(click to skip)</span>
      </p>
    </div>
  )
}
