import { useEffect, useId, useRef } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { ChevronRight } from 'lucide-react'
import { formatPrice } from '@/lib/format'
import { productGradient } from '@/lib/product-colors'
import { burstCurrency } from '@/lib/particles'
import { useReducedMotion } from '@/hooks/use-reduced-motion'
import { ProductAvatar } from '@/components/dashboard/ProductAvatar'
import { DeltaPill } from '@/components/dashboard/DeltaPill'
import { deltaDirection } from '@/lib/delta'
import { ProductAvailabilityBadge } from '@/components/dashboard/AvailabilityBadge'
import { Sparkline } from '@/components/dashboard/Sparkline'
import { ListingPanel } from '@/components/dashboard/ListingPanel'
import type { TrackedProduct } from '@/lib/types'

/**
 * One product row (#144): summary line + expandable per-shop listings.
 *
 * A11y shape (acceptance criteria):
 * - The expand trigger is a SCOPED semantic <button> around the product
 *   name with `aria-expanded`/`aria-controls` — NOT a button wrapping the
 *   whole row. Whole-row clickability comes from a stretched ::after
 *   overlay on the button; other interactive elements (sparkline tooltip
 *   trigger, listing links) are DOM SIBLINGS lifted above the overlay with
 *   z-10, never button descendants (invalid HTML).
 * - The revealed panel is role="region" aria-labelledby={the product-NAME
 *   element}, so screen readers announce it cleanly.
 */

/** Stagger/count-up cost cap: rows past this index render immediately. */
export const ANIMATED_ROW_CAP = 15

interface ProductRowProps {
  product: TrackedProduct
  index: number
  expanded: boolean
  onToggle: () => void
  /** Set for one render pass when a live price drop should celebrate. */
  celebrate: boolean
}

export function ProductRow({ product, index, expanded, onToggle, celebrate }: ProductRowProps) {
  const reducedMotion = useReducedMotion()
  const nameId = useId()
  const panelId = useId()
  const priceRef = useRef<HTMLSpanElement | null>(null)
  const accent = productGradient(product.name).from

  // Price-drop celebration: green row pulse (CSS keyframes) + coin burst at
  // the price element. Fully disabled under reduced motion.
  const rowRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!celebrate || reducedMotion) return
    const row = rowRef.current
    if (row) {
      row.classList.remove('celebrate')
      void row.offsetWidth // restart the CSS animation
      row.classList.add('celebrate')
    }
    const rect = priceRef.current?.getBoundingClientRect()
    if (rect) burstCurrency(rect.left + rect.width / 2, rect.top, { count: 16, spread: 130, size: 16 })
  }, [celebrate, reducedMotion])

  const animateIn = !reducedMotion && index < ANIMATED_ROW_CAP

  const convertedPrice = formatPrice(product.bestPriceConverted, product.bestPriceConvertedCurrency)
  const originalPrice = formatPrice(product.bestPriceOriginal, product.bestPriceOriginalCurrency)
  const showOriginal =
    originalPrice !== null &&
    product.bestPriceOriginalCurrency !== null &&
    product.bestPriceOriginalCurrency !== product.bestPriceConvertedCurrency

  const tone =
    product.delta7d === null ? 'flat' : (
      { down: 'good', up: 'bad', flat: 'flat' } as const
    )[deltaDirection(product.delta7d)]

  const shopCount = product.availability.total

  return (
    <motion.div
      ref={rowRef}
      initial={animateIn ? { opacity: 0, y: 12 } : false}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, ease: [0.2, 0.7, 0.3, 1], delay: index * 0.04 }}
      className="prow border-b border-line last:border-b-0"
      style={{ '--pa': accent } as React.CSSProperties}
      data-expanded={expanded || undefined}
    >
      <div
        className={`relative flex flex-wrap items-center gap-x-3 gap-y-1.5 px-4.5 py-3.5 transition-colors md:grid md:grid-cols-[minmax(190px,1fr)_76px_132px_92px_130px_32px] ${
          expanded
            ? 'bg-[color-mix(in_srgb,var(--pa)_12%,var(--surface))] shadow-[inset_3px_0_0_var(--pa)]'
            : 'hover:bg-surface-2'
        }`}
      >
        <span className="flex w-full min-w-0 items-center gap-3 md:w-auto">
          <ProductAvatar name={product.name} imageUrl={product.imageUrl} category={product.category} />
          <span className="min-w-0">
            {/* Scoped expand trigger; after:inset-0 stretches its hit area
                across the row without nesting other interactive elements. */}
            <button
              type="button"
              onClick={onToggle}
              aria-expanded={expanded}
              aria-controls={expanded ? panelId : undefined}
              className="block w-full min-w-0 text-start after:absolute after:inset-0 after:content-[''] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-iris"
            >
              <span id={nameId} className="block truncate text-[14.5px] font-[650] tracking-tight">
                <bdi>{product.name}</bdi>
              </span>
            </button>
            <span className="block truncate text-xs text-ink-muted">
              {shopCount === 0 ? (
                'No shops tracked'
              ) : (
                <>
                  {shopCount} {shopCount === 1 ? 'shop' : 'shops'}
                  {product.bestPriceShop !== null && (
                    <>
                      {' · best at '}
                      <bdi>{product.bestPriceShop}</bdi>
                    </>
                  )}
                </>
              )}
            </span>
          </span>
        </span>

        <span className="hidden md:block">
          <Sparkline series={product.sparkline} currency={product.bestPriceConvertedCurrency} tone={tone} />
        </span>

        <span className="md:text-right">
          {convertedPrice !== null ? (
            <>
              <span ref={priceRef} className="font-num text-[17px] font-semibold tracking-tight tabular-nums">
                <bdi>{convertedPrice}</bdi>
              </span>
              {showOriginal && (
                <span className="block text-[10px] text-ink-faint">
                  <bdi>{originalPrice}</bdi> at source
                </span>
              )}
              {product.conversionStale && (
                <span className="block text-[10px] font-semibold text-warn">
                  Rate outdated
                  {product.conversionAsOf !== null && ` (as of ${product.conversionAsOf})`}
                </span>
              )}
              {product.mixedCurrencies && (
                <span className="block text-[10px] text-ink-faint">mixed currencies</span>
              )}
            </>
          ) : (
            <span className="text-sm text-ink-faint">No price yet</span>
          )}
        </span>

        <span className="md:justify-self-end">
          <DeltaPill delta={product.delta7d} />
        </span>

        <span className="md:justify-self-start">
          <ProductAvailabilityBadge rollup={product.availability} />
        </span>

        <span
          aria-hidden="true"
          className={`ms-auto text-ink-faint transition-transform duration-200 md:ms-0 md:justify-self-center ${expanded ? 'rotate-90' : ''}`}
        >
          <ChevronRight className="size-4" strokeWidth={2.4} />
        </span>
      </div>

      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            id={panelId}
            role="region"
            aria-labelledby={nameId}
            initial={reducedMotion ? false : { height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={reducedMotion ? undefined : { height: 0, opacity: 0 }}
            transition={{ duration: 0.28, ease: 'easeInOut' }}
            className="overflow-hidden bg-[color-mix(in_srgb,var(--pa)_5%,var(--surface-2))]"
          >
            <ListingPanel productId={product.id} open={expanded} />
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  )
}
