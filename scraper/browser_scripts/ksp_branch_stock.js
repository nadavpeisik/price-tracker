// Ask KSP for one catalog id's per-branch stock (issue #196).
//
// Runs INSIDE the page (page.evaluate) rather than out-of-band, and that is the whole point:
// KSP sits behind Cloudflare, and a replay from Playwright's APIRequestContext gets a 403 even
// though it shares the context's cookie jar (verified live across 10 items). A document-context
// fetch carries the Cloudflare clearance cookie *plus* the Origin / Referer / sec-fetch-site
// headers the browser attaches to a same-page request, which is what actually passes the wall.
//
// The path is deliberately RELATIVE: it resolves against the page's own origin, so this can only
// ever talk to the KSP host the caller already validated — never off-site, whatever `catalog` is.
// encodeURIComponent keeps the catalog id a single path segment (ids are not always numeric — one
// live item returns "F000029"), so it cannot traverse into another endpoint.
//
// Returns the parsed body, or null on a non-2xx. Both a rejected fetch and an unparseable body
// throw, which the caller turns into UNKNOWN — availability degrades, the price still returns.
async ({ catalog, timeoutMs }) => {
  const res = await fetch('/m_action/api/mlay/' + encodeURIComponent(catalog), {
    credentials: 'include',
    signal: AbortSignal.timeout(timeoutMs),
  });
  return res.ok ? await res.json() : null;
}
