/**
 * djb2 string hash — stable, fast, good-enough spread for hashing a
 * normalized name into a small fixed palette. Shared by the shop-color and
 * product-gradient assignments so the two can't silently diverge.
 */
export function hashString(value: string): number {
  let hash = 5381
  for (let i = 0; i < value.length; i++) {
    hash = (hash * 33) ^ value.charCodeAt(i)
  }
  return hash >>> 0 // force unsigned so a downstream modulo can't go negative
}
