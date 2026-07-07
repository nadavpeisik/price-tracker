/**
 * Currency-symbol particle burst (#144) — shared by the rocket intro and the
 * price-drop celebration, ported from the mockup. Uses the Web Animations
 * API directly (short-lived DOM nodes, no React state churn).
 *
 * Callers gate on reduced motion; this module also self-guards for
 * environments without WAAPI (jsdom).
 */
const SYMBOLS = ['₪', '$', '€', '£', '¥', '₿', '₩']
const COLORS = ['#5A57D6', '#0C9B50', '#E23B44', '#C67F09', '#0FA097', '#8155E6', '#2F6FE0', '#D63C93']

export interface BurstOptions {
  /** Number of particles. */
  count: number
  /** Base travel distance in px. */
  spread: number
  /** Base font size in px. */
  size: number
}

export function burstCurrency(x: number, y: number, { count, spread, size }: BurstOptions): void {
  for (let i = 0; i < count; i++) {
    const span = document.createElement('span')
    if (typeof span.animate !== 'function') return // no WAAPI (tests) — skip quietly
    span.className = 'particle'
    span.textContent = SYMBOLS[Math.floor(Math.random() * SYMBOLS.length)]
    span.style.left = `${x}px`
    span.style.top = `${y}px`
    span.style.color = COLORS[Math.floor(Math.random() * COLORS.length)]
    span.style.fontSize = `${size + Math.random() * size * 0.6}px`
    document.body.appendChild(span)

    const angle = Math.random() * Math.PI * 2
    const distance = spread * (0.4 + Math.random() * 0.8)
    const dx = Math.cos(angle) * distance
    const dy = Math.sin(angle) * distance - spread * 0.3 // slight upward bias
    const rotation = Math.random() * 720 - 360

    const animation = span.animate(
      [
        { transform: 'translate(-50%,-50%) rotate(0deg) scale(.4)', opacity: 1 },
        {
          transform: `translate(${dx}px,${dy}px) rotate(${rotation}deg) scale(1)`,
          opacity: 1,
          offset: 0.7,
        },
        {
          transform: `translate(${dx * 1.25}px,${dy * 1.25 + 60}px) rotate(${rotation}deg) scale(.9)`,
          opacity: 0,
        },
      ],
      { duration: 1100 + Math.random() * 500, easing: 'cubic-bezier(.2,.6,.4,1)' },
    )
    animation.onfinish = () => span.remove()
    animation.oncancel = () => span.remove()
  }
}
