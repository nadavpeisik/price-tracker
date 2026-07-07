/**
 * Deterministic avatar gradients for products without an image (#144).
 *
 * Same normalize-and-hash approach as shop colors: a stable gradient pair
 * per product name. The vivid mid-tone hues read well on both themes (as in
 * the mockup, which reuses one gradient set across light/dark). The first
 * hue doubles as the product accent (`--pa`) that tints the expanded row.
 */
import { normalizeShopName } from '@/lib/shop-colors'

export interface ProductGradient {
  from: string
  to: string
}

const GRADIENT_PAIRS: readonly ProductGradient[] = [
  { from: '#8155E6', to: '#2F6FE0' }, // violet → blue
  { from: '#0FA097', to: '#40CE82' }, // teal → green
  { from: '#D63C93', to: '#E23B44' }, // magenta → red
  { from: '#2F6FE0', to: '#0FA097' }, // blue → teal
  { from: '#8155E6', to: '#D63C93' }, // violet → magenta
  { from: '#C67F09', to: '#E23B44' }, // amber → red
  { from: '#0E7FA8', to: '#8155E6' }, // cyan → violet
  { from: '#C05C09', to: '#D63C93' }, // orange → magenta
] as const

function hashString(value: string): number {
  let hash = 5381
  for (let i = 0; i < value.length; i++) {
    hash = (hash * 33) ^ value.charCodeAt(i)
  }
  return hash >>> 0
}

export function productGradient(name: string): ProductGradient {
  // Normalization rules are shared with shop colors (trim/NFC/lowercase).
  return GRADIENT_PAIRS[hashString(normalizeShopName(name)) % GRADIENT_PAIRS.length]
}
