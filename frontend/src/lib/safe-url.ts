/**
 * URL scheme guard for machine-scraped shop URLs (#144).
 *
 * Listing URLs come from arbitrary scraped sites, so they are validated
 * before ever being bound to an href — an XSS guard consistent with the
 * Phase 1.7 machine-URL threat model. The backend UrlValidator is
 * authoritative (it already rejects non-http(s) at track time); this is
 * cheap defense-in-depth, NOT a reimplementation of SSRF rules (private-IP
 * blocking stays server-side).
 *
 * Parse with `new URL(url)` WITHOUT a base: relative and protocol-relative
 * strings ("//evil.com", "foo/bar") throw instead of resolving against
 * window.location, where they could masquerade as same-origin http(s).
 */
export function safeExternalHref(url: string): string | null {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return null
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
  return parsed.href
}
