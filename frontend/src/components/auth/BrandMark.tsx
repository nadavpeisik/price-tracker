/**
 * The PriceHunt mark + wordmark, shared by the shell header and the
 * pre-auth screens (#248) so the brand never drifts between them.
 */
export function BrandMark() {
  return (
    <div className="flex items-center gap-2.5">
      <span
        aria-hidden="true"
        className="relative size-[30px] rounded-[9px] bg-[linear-gradient(145deg,#D63C93,var(--iris)_55%,#2F6FE0)] shadow-[0_5px_14px_-4px_color-mix(in_srgb,var(--iris)_60%,transparent)] after:absolute after:inset-[9px] after:rounded-full after:border-[2.5px] after:border-surface after:content-['']"
      />
      <span className="font-display text-xl font-bold tracking-tight">PriceHunt</span>
    </div>
  )
}
