import { describe, expect, it } from 'vitest'
import { safeExternalHref } from '@/lib/safe-url'

describe('safeExternalHref (scraped-URL scheme guard)', () => {
  it('accepts absolute http/https URLs', () => {
    expect(safeExternalHref('https://ksp.co.il/web/item/1')).toBe('https://ksp.co.il/web/item/1')
    expect(safeExternalHref('http://example.com/a?b=c')).toBe('http://example.com/a?b=c')
  })

  it('rejects javascript: and other dangerous schemes', () => {
    expect(safeExternalHref('javascript:alert(1)')).toBeNull()
    expect(safeExternalHref('data:text/html,<script>1</script>')).toBeNull()
    expect(safeExternalHref('vbscript:x')).toBeNull()
    expect(safeExternalHref('file:///etc/passwd')).toBeNull()
  })

  it('rejects protocol-relative and relative URLs (no base resolution)', () => {
    expect(safeExternalHref('//evil.com/x')).toBeNull()
    expect(safeExternalHref('/relative/path')).toBeNull()
    expect(safeExternalHref('www.example.com')).toBeNull()
    expect(safeExternalHref('')).toBeNull()
  })
})
