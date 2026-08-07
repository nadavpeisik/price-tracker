import { ArrowDown, ArrowUp } from 'lucide-react'
import { formatDeltaPct } from '@/lib/format'
import { deltaDirection } from '@/lib/delta'

/**
 * 7-day delta pill (#144): green ▼ drop / red ▲ rise / neutral flat, or the
 * `New` tag when the product has under 7 days of history.
 *
 * The `New` guard is a STRICT null check — a real flat delta of 0 is falsy
 * but must render as flat, never as `New`.
 */

export function DeltaPill({ delta }: { delta: number | null }) {
  if (delta === null) {
    return (
      <span
        className="inline-flex items-center rounded-lg bg-iris-soft px-2 py-0.5 text-[12.5px] font-bold text-iris-strong"
        aria-label="7-day change: new — under 7 days of history"
      >
        New
      </span>
    )
  }

  const direction = deltaDirection(delta)
  if (direction === 'flat') {
    return (
      <span
        className="inline-flex items-center gap-0.5 rounded-lg bg-surface-2 px-2 py-0.5 text-[12.5px] font-bold text-ink-muted tabular-nums"
        aria-label="7-day change: flat"
      >
        0%
      </span>
    )
  }

  const isDown = direction === 'down'
  return (
    <span
      className={`inline-flex items-center gap-0.5 rounded-lg px-2 py-0.5 text-[12.5px] font-bold tabular-nums ${
        isDown ? 'bg-good-soft text-good-strong' : 'bg-bad-soft text-bad'
      }`}
      aria-label={`7-day change: ${isDown ? 'down' : 'up'} ${formatDeltaPct(delta)}`}
    >
      {isDown ? (
        <ArrowDown className="size-3" strokeWidth={3} aria-hidden="true" />
      ) : (
        <ArrowUp className="size-3" strokeWidth={3} aria-hidden="true" />
      )}
      {formatDeltaPct(delta)}
    </span>
  )
}
