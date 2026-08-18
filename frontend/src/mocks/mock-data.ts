/**
 * Typed mock data for the dashboard (#144) — stands in for the backend
 * dashboard endpoint (#146) + delta/sparkline engine (#145) until they land.
 *
 * The BUILDER is the canonical home of the 7-day baseline-rule fixtures
 * (sparse history, exactly-7d, under-7d → null, missing current price,
 * delta exactly 0) — the frontend renders `delta7d`/`sparkline` as SUPPLIED
 * fields and never recomputes them; tests assert the builder, not UI math.
 *
 * DEV-ONLY: imported solely behind `import.meta.env.DEV` (api-client.ts), so
 * Rollup dead-code-eliminates this module from production bundles. The
 * sentinel below exists so CI can verify that (post-build grep must NOT find
 * it in dist).
 */
import type {
  AvailabilityRollup,
  Listing,
  ListingAvailability,
  PricePoint,
  TrackedProduct,
} from '@/lib/types'

/** Unique mock-only token for the CI bundle-sentinel check — see ci.yml. */
export const MOCK_SENTINEL = '__MOCK_DATA_SENTINEL__'

// Side-effectful global marker: if this module ever reaches a bundle, the
// sentinel string reaches it too (an unused const alone would be
// tree-shaken, silently defeating the CI grep). Also handy at runtime to
// tell mock mode apart from live data.
;(globalThis as Record<string, unknown>).__PRICEHUNT_MOCK__ = MOCK_SENTINEL

const DAY_MS = 24 * 60 * 60 * 1000
const HOUR_MS = 60 * 60 * 1000

/* ── baseline rule (#145 semantics, mock implementation) ─────────────── */

/**
 * 7-day delta: last point vs the latest point AT OR BEFORE `now − 7d`
 * (nearest-earlier sample, irregular history, no interpolation). Under 7
 * days of history → null (renders as `New`, never a misleading 0%).
 */
export function computeDelta7d(series: PricePoint[], now: number): number | null {
  if (series.length === 0) return null
  const current = Number(series[series.length - 1].price)
  if (!Number.isFinite(current)) return null
  const cutoff = now - 7 * DAY_MS
  let baseline: number | null = null
  for (const point of series) {
    if (Date.parse(point.t) <= cutoff) baseline = Number(point.price)
    else break
  }
  if (baseline === null || !Number.isFinite(baseline) || baseline === 0) return null
  return ((current - baseline) / baseline) * 100
}

/* ── raw fixture specs ───────────────────────────────────────────────── */

interface RawListingSpec {
  shop: string
  url: string
  currency: string | null
  availability: ListingAvailability
  /** Hours before `now`; null → "never checked". */
  lastCheckedHoursAgo: number | null
  /** [daysAgo, price][] — oldest first. Empty → no price history. */
  history: [number, number][]
  /** Force "no latest price" even with history (backend nullability is real). */
  currentPriceMissing?: boolean
  /**
   * The current price already converted into the display currency (ILS) —
   * the mock's stand-in for the backend's FX conversion (#157). Omit for ILS
   * listings (identity). The mock never converts anything itself.
   */
  currentPriceConverted?: number
  /** The FX rate behind `currentPriceConverted` was stale → "Rate outdated". */
  conversionStale?: boolean
}

interface RawProductSpec {
  id: number
  name: string
  category: string | null
  listings: RawListingSpec[]
  mixedCurrencies?: boolean
  conversionStale?: boolean
  conversionAsOf?: string | null
  /**
   * Pre-normalized product series for cross-currency products — the mock's
   * stand-in for #145's FX engine output (the client never converts).
   */
  normalizedSparkline?: [number, number][]
}

const iso = (now: number, daysAgo: number) => new Date(now - daysAgo * DAY_MS).toISOString()

const toSeries = (now: number, history: [number, number][]): PricePoint[] =>
  history
    .slice()
    .sort((a, b) => b[0] - a[0]) // oldest (largest daysAgo) first
    .map(([daysAgo, price]) => ({ t: iso(now, daysAgo), price: price.toFixed(2) }))

/* ── the catalog ─────────────────────────────────────────────────────── */

function specs(): RawProductSpec[] {
  const handcrafted: RawProductSpec[] = [
    {
      // Clear 7d drop, 3 shops, all in stock — the "happy row".
      id: 1,
      name: 'Sony WH-1000XM5',
      category: 'headphones',
      listings: [
        {
          shop: 'Ivory',
          url: 'https://www.ivory.co.il/catalog.php?id=1001',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 2,
          history: [
            [14, 1330],
            [11, 1319],
            [9, 1305],
            [7, 1298],
            [4, 1288],
            [2, 1282],
            [0.2, 1279],
          ],
        },
        {
          shop: 'KSP',
          url: 'https://ksp.co.il/web/item/1002',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 3,
          history: [
            [13, 1385],
            [8, 1349],
            [5, 1320],
            [1, 1303],
          ],
        },
        {
          shop: 'Bug',
          url: 'https://www.bug.co.il/product/1003',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 5,
          history: [
            [12, 1390],
            [6, 1370],
            [3, 1360],
            [0.5, 1349],
          ],
        },
      ],
    },
    {
      // Biggest drop — feeds the "biggest drop" tile.
      id: 2,
      name: 'Logitech MX Master 3S',
      category: 'mouse',
      listings: [
        {
          shop: 'Bug',
          url: 'https://www.bug.co.il/product/2001',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 1,
          history: [
            [15, 454],
            [10, 438],
            [8, 428],
            [6, 419],
            [3, 405],
            [0.3, 399],
          ],
        },
        {
          shop: 'KSP',
          url: 'https://ksp.co.il/web/item/2002',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 4,
          history: [
            [14, 455],
            [7, 446],
            [2, 431],
          ],
        },
      ],
    },
    {
      // Price RISE (red delta), 2 shops.
      id: 3,
      name: 'LG C3 55" OLED evo TV',
      category: 'tv',
      listings: [
        {
          shop: 'TMS',
          url: 'https://tms.co.il/item/3001',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 6,
          history: [
            [12, 4390],
            [9, 4440],
            [7, 4470],
            [4, 4530],
            [1, 4590],
          ],
        },
        {
          shop: 'Ivory',
          url: 'https://www.ivory.co.il/catalog.php?id=3002',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 8,
          history: [
            [11, 4500],
            [5, 4620],
            [2, 4690],
          ],
        },
      ],
    },
    {
      // Delta EXACTLY 0 (flat pill, NOT `New` — strict null check fixture)
      // + an UNKNOWN listing → MIXED rollup + amber badge.
      id: 4,
      name: 'Dell UltraSharp U2723QE 4K',
      category: 'monitor',
      listings: [
        {
          shop: 'Ivory',
          url: 'https://www.ivory.co.il/catalog.php?id=4001',
          currency: 'ILS',
          availability: 'UNKNOWN',
          lastCheckedHoursAgo: 30,
          history: [
            [10, 2150],
            [8, 2150],
            [3, 2150],
            [0.4, 2150],
          ],
        },
        {
          shop: 'TMS',
          url: 'https://tms.co.il/item/4002',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 7,
          history: [
            [9, 2210],
            [5, 2205],
            [1, 2199],
          ],
        },
      ],
    },
    {
      // Under 7 days of history → delta7d null → `New` tag.
      id: 5,
      name: 'Apple AirPods Pro 2 (USB-C)',
      category: 'earbuds',
      listings: [
        {
          shop: 'Bug',
          url: 'https://www.bug.co.il/product/5001',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 2,
          history: [
            [5, 830],
            [3, 810],
            [1, 796],
            [0.1, 789],
          ],
        },
        {
          shop: 'KSP',
          url: 'https://ksp.co.il/web/item/5002',
          currency: 'ILS',
          availability: 'UNAVAILABLE',
          lastCheckedHoursAgo: 3,
          history: [
            [4, 869],
            [2, 838],
            [0.2, 799],
          ],
        },
      ],
    },
    {
      // Exactly-7d baseline point (boundary fixture: "at or before" includes it).
      id: 6,
      name: 'Samsung 990 Pro 2TB NVMe',
      category: 'ssd',
      listings: [
        {
          shop: 'KSP',
          url: 'https://ksp.co.il/web/item/6001',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 12,
          history: [
            [10, 735],
            [7, 730], // exactly now − 7d → the baseline
            [4, 737],
            [0.6, 739],
          ],
        },
        {
          shop: 'Bug',
          url: 'https://www.bug.co.il/product/6002',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 18,
          history: [
            [9, 740],
            [3, 746],
            [0.8, 749],
          ],
        },
      ],
    },
    {
      // Hebrew name + Hebrew shop (bidi fixtures) + mixed currencies with a
      // stale FX snapshot; normalized series supplied (as #145 will).
      id: 7,
      name: 'מקלדת Keychron K8 Pro',
      category: 'keyboard',
      mixedCurrencies: true,
      conversionStale: true,
      conversionAsOf: null, // stale AND no snapshot date → "Rate outdated" without a date
      // FX-normalized best (backend's job): Amazon's $102 converts to ₪382
      // (see its `currentPriceConverted`), beating אלקטרה's native ₪385. The
      // header shows ₪382 (display) with "$102 at source"; the sparkline
      // below is the normalized ILS series.
      normalizedSparkline: [
        [12, 420],
        [9, 415],
        [7, 409],
        [4, 399],
        [1, 389],
        [0.2, 382],
      ],
      listings: [
        {
          shop: 'אלקטרה',
          url: 'https://www.electra.co.il/product/7001',
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 9,
          history: [
            [12, 420],
            [7, 409],
            [1, 389],
            [0.2, 385],
          ],
        },
        {
          shop: 'Amazon',
          url: 'https://www.amazon.com/dp/B0B2A1C1',
          currency: 'USD',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: 26,
          history: [
            [11, 119],
            [6, 110],
            [2, 102],
          ],
          currentPriceConverted: 382, // $102 at the mock's stale rate
          conversionStale: true,
        },
      ],
    },
    {
      // All listings out of stock → UNAVAILABLE rollup (red badge).
      id: 8,
      name: 'Nintendo Switch 2',
      category: 'console',
      listings: [
        {
          shop: 'KSP',
          url: 'https://ksp.co.il/web/item/8001',
          currency: 'ILS',
          availability: 'UNAVAILABLE',
          lastCheckedHoursAgo: 4,
          history: [
            [13, 2399],
            [8, 2399],
            [2, 2349],
          ],
        },
        {
          shop: 'Ivory',
          url: 'https://www.ivory.co.il/catalog.php?id=8002',
          currency: 'ILS',
          availability: 'UNAVAILABLE',
          lastCheckedHoursAgo: 5,
          history: [
            [12, 2449],
            [6, 2399],
            [1, 2380],
          ],
        },
      ],
    },
    {
      // No latest price (nullability fixture): "no price yet" placeholder,
      // sorts LAST under price sorts; single-point sparkline (degenerate X).
      id: 9,
      name: 'Framework Laptop 16',
      category: 'laptop',
      listings: [
        {
          shop: 'TMS',
          url: 'https://tms.co.il/item/9001',
          currency: null,
          availability: 'UNKNOWN',
          lastCheckedHoursAgo: null, // "never checked"
          history: [[9, 8890]],
          currentPriceMissing: true,
        },
      ],
    },
    {
      // Zero tracked listings (total 0): "No shops tracked", neutral price,
      // empty-but-valid expansion, EMPTY sparkline (degenerate case).
      id: 10,
      name: 'Bambu Lab A1 mini',
      category: null,
      listings: [],
    },
  ]

  // Filler products so pagination is real (> one page at size 20).
  const fillers: RawProductSpec[] = Array.from({ length: 16 }, (_, i) => {
    const id = 100 + i
    const base = 150 + i * 37
    const drift = ((i % 5) - 2) * 4 // deterministic mild drop/rise/flat mix
    return {
      id,
      name: `USB-C Hub ${i + 1}-port`,
      category: 'accessory',
      listings: [
        {
          shop: i % 2 === 0 ? 'KSP' : 'Bug',
          url: `https://example-shop.co.il/item/${id}`,
          currency: 'ILS',
          availability: 'AVAILABLE',
          lastCheckedHoursAgo: (i % 12) + 1,
          history: [
            [12, base],
            [8, base + drift],
            [4, base + drift * 2],
            [0.5, base + drift * 2],
          ],
        },
      ],
    } satisfies RawProductSpec
  })

  return [...handcrafted, ...fillers]
}

/* ── builder ─────────────────────────────────────────────────────────── */

export interface MockDbEntry {
  product: TrackedProduct
  listings: Listing[]
}

function rollup(listings: RawListingSpec[]): AvailabilityRollup {
  const total = listings.length
  const availableCount = listings.filter((l) => l.availability === 'AVAILABLE').length
  if (total === 0) return { status: 'UNKNOWN', availableCount: 0, total: 0 }
  if (availableCount === total) return { status: 'AVAILABLE', availableCount, total }
  if (listings.every((l) => l.availability === 'UNAVAILABLE'))
    return { status: 'UNAVAILABLE', availableCount, total }
  if (availableCount > 0) return { status: 'MIXED', availableCount, total }
  return { status: 'UNKNOWN', availableCount, total }
}

function currentPrice(spec: RawListingSpec): string | null {
  if (spec.currentPriceMissing || spec.history.length === 0) return null
  const latest = spec.history.reduce((a, b) => (a[0] < b[0] ? a : b))
  return latest[1].toFixed(2)
}

const DISPLAY_CURRENCY = 'ILS'

export function buildMockDb(now: number): MockDbEntry[] {
  return specs().map((spec) => {
    // Listings first — the wire shape of GET /api/products/{id}/listings
    // (#157). `priceConverted` is either the spec's already-converted value
    // or, for display-currency listings, the price itself; the mock never
    // compares or converts across currencies (that's the backend's FX job).
    const listings: Listing[] = spec.listings.map((l, i) => {
      const priceOriginal = currentPrice(l)
      const priceConverted =
        priceOriginal === null
          ? null
          : l.currentPriceConverted !== undefined
            ? l.currentPriceConverted.toFixed(2)
            : l.currency === DISPLAY_CURRENCY
              ? priceOriginal
              : null // foreign currency without a supplied conversion → unconvertible
      return {
        trackedItemId: spec.id * 1000 + i,
        shopName: l.shop,
        url: l.url,
        priceOriginal,
        priceOriginalCurrency: priceOriginal === null ? null : l.currency,
        priceConverted,
        priceConvertedCurrency: priceConverted === null ? null : DISPLAY_CURRENCY,
        conversionStale: priceConverted !== null && (l.conversionStale ?? false),
        availability: l.availability,
        lastChecked:
          l.lastCheckedHoursAgo === null
            ? null
            : new Date(now - l.lastCheckedHoursAgo * HOUR_MS).toISOString(),
      }
    })

    // The winning listing, selected ONCE and then every best-* field derived
    // from it — so price, shop, currency and bestTrackedItemId can never drift
    // apart. Rule: cheapest converted price among priced + not-out-of-stock,
    // ties by lower id. NARROWER than the backend's (no TTL, positivity or
    // convertibility rules — the mock has no clock-relative freshness beyond
    // what the fixtures encode); the winner here is a demo, not the engine.
    const best = listings.reduce<Listing | null>((winner, l) => {
      if (l.priceConverted === null || l.availability === 'UNAVAILABLE') return winner
      if (winner === null || Number(l.priceConverted) < Number(winner.priceConverted)) return l
      return winner
    }, null)
    const bestSpec = best === null ? null : spec.listings[listings.indexOf(best)]

    const sparkline: PricePoint[] = spec.normalizedSparkline
      ? toSeries(now, spec.normalizedSparkline)
      : bestSpec
        ? toSeries(now, bestSpec.history)
        : []

    const product: TrackedProduct = {
      id: spec.id,
      name: spec.name,
      imageUrl: null, // backend image endpoint is a separate SSRF-safe track
      category: spec.category,
      bestPriceConverted: best?.priceConverted ?? null,
      bestPriceConvertedCurrency: best === null ? null : DISPLAY_CURRENCY,
      bestPriceOriginal: best?.priceOriginal ?? null,
      bestPriceOriginalCurrency: best?.priceOriginalCurrency ?? null,
      bestPriceShop: best?.shopName ?? null,
      bestTrackedItemId: best?.trackedItemId ?? null,
      conversionStale: spec.conversionStale ?? false,
      conversionAsOf: spec.conversionAsOf ?? null,
      mixedCurrencies: spec.mixedCurrencies ?? false,
      availability: rollup(spec.listings),
      delta7d: best === null ? null : computeDelta7d(sparkline, now),
      sparkline,
    }

    return { product, listings }
  })
}
