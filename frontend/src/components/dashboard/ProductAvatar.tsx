import { useState } from 'react'
import {
  Cable,
  Ear,
  Gamepad2,
  HardDrive,
  Headphones,
  Keyboard,
  Laptop,
  Monitor,
  Mouse,
  Tv,
  type LucideIcon,
} from 'lucide-react'
import { productGradient } from '@/lib/product-colors'

/**
 * Product avatar (#144): real photo when `imageUrl` is present (it stays
 * null until the SSRF-safe backend image endpoint lands), else a
 * deterministic gradient with a category icon or the name's initial.
 *
 * The avatar is DECORATIVE — the adjacent name already reads it, so
 * `alt=""` + `aria-hidden` keep screen readers from announcing it twice.
 * Fixed 38px box (no CLS), lazy loading, cover fit, `onerror` → fallback,
 * and `referrerpolicy="no-referrer"` for scraped third-party origins.
 */

const CATEGORY_ICONS: Record<string, LucideIcon> = {
  headphones: Headphones,
  earbuds: Ear,
  mouse: Mouse,
  keyboard: Keyboard,
  monitor: Monitor,
  tv: Tv,
  ssd: HardDrive,
  laptop: Laptop,
  console: Gamepad2,
  accessory: Cable,
}

interface ProductAvatarProps {
  name: string
  imageUrl: string | null
  category?: string | null
}

export function ProductAvatar({ name, imageUrl, category }: ProductAvatarProps) {
  const [imageFailed, setImageFailed] = useState(false)

  if (imageUrl !== null && !imageFailed) {
    return (
      <img
        src={imageUrl}
        alt=""
        aria-hidden="true"
        width={38}
        height={38}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setImageFailed(true)}
        className="size-[38px] flex-none rounded-[11px] object-cover shadow-sm"
      />
    )
  }

  const gradient = productGradient(name)
  const Icon = category ? CATEGORY_ICONS[category] : undefined
  const initial = name.trim().charAt(0).toUpperCase()

  return (
    <span
      aria-hidden="true"
      className="grid size-[38px] flex-none place-items-center rounded-[11px] font-display text-[15px] font-bold text-white shadow-sm"
      style={{ background: `linear-gradient(140deg, ${gradient.from}, ${gradient.to})` }}
    >
      {Icon ? <Icon className="size-5" strokeWidth={2} /> : initial}
    </span>
  )
}
