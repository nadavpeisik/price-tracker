/**
 * Display formatting helpers (#144).
 *
 * Currency always goes through Intl.NumberFormat with the value's own
 * currency code — symbols/grouping are never hardcoded (the mockup's `₪` +
 * en-US concat was mockup-only). Prices arrive as decimal strings
 * (BigDecimal on the wire); parsing to a number happens ONLY here, at the
 * display boundary.
 */

const formatterCache = new Map<string, Intl.NumberFormat>()

/** Return a cached `Intl.NumberFormat` for the given ISO currency, creating
 *  (and caching) one on first use. Formatters are relatively expensive to
 *  construct, and the same handful of currencies recur across every row. */
function currencyFormatter(currency: string): Intl.NumberFormat {
  let fmt = formatterCache.get(currency)
  if (!fmt) {
    fmt = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      minimumFractionDigits: 0,
      maximumFractionDigits: 2,
    })
    formatterCache.set(currency, fmt)
  }
  return fmt
}

/**
 * Format a decimal-string price in its currency. Returns null when either
 * part is missing (callers render a neutral placeholder, never 0) or the
 * string does not parse.
 */
export function formatPrice(price: string | null, currency: string | null): string | null {
  if (price === null || currency === null) return null
  const value = Number(price)
  if (!Number.isFinite(value)) return null
  try {
    return currencyFormatter(currency).format(value)
  } catch {
    // Unknown/invalid ISO code from a scraped source — degrade gracefully.
    return `${price} ${currency}`
  }
}

/** "▼ 6.0%"-style magnitude for the delta pill (sign is conveyed by style). */
export function formatDeltaPct(delta: number): string {
  return `${Math.abs(delta).toFixed(1)}%`
}

/**
 * Relative "checked 3h ago" freshness for a lastChecked timestamp, evaluated
 * against a caller-supplied `now` (the shared ticker). Clock drift between
 * client and server can make the delta negative — clamp to "just now".
 */
export function formatRelativeTime(iso: string | null, now: number): string {
  if (iso === null) return 'never checked'
  const then = Date.parse(iso)
  if (Number.isNaN(then)) return 'never checked'
  const seconds = Math.max(0, Math.floor((now - then) / 1000))
  if (seconds < 60) return 'checked just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `checked ${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `checked ${hours}h ago`
  const days = Math.floor(hours / 24)
  return `checked ${days}d ago`
}
