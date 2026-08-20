// @ts-check
// Ask KSP for one catalog id's per-branch stock (issue #196).
//
// Runs INSIDE the page (page.evaluate) rather than out-of-band, and that is the whole point:
// KSP sits behind Cloudflare, and a replay from Playwright's APIRequestContext gets a 403 even
// though it shares the context's cookie jar (verified live across 10 items). A document-context
// fetch carries the Cloudflare clearance cookie *plus* the Origin / Referer / sec-fetch-site
// headers the browser attaches to a same-page request, which is what actually passes the wall.
//
// The origin check is NOT redundant with the caller's. extract() validates page.url, then waits
// up to _PRICE_DEADLINE_S draining the SSE queue; a page that navigates during that window would
// resolve the relative path below against the NEW origin, sending a credentialed request to
// whatever site it landed on and accepting its stock JSON as KSP's. Re-checking here closes that
// window because the check and the fetch happen in one synchronous step — the page cannot
// navigate between them. (Codex adversarial review, #196.)
//
// The path stays RELATIVE so it can only ever address the origin just verified, and
// encodeURIComponent keeps the catalog id a single path segment (ids are not always numeric —
// one live item returns "F000029"), so it cannot traverse into another endpoint.
//
// Returns the parsed body, or null on a non-2xx. A wrong origin, a rejected fetch and an
// unparseable body all throw, which the caller turns into UNKNOWN — availability degrades, the
// price still returns.
/**
 * @param {{catalog: string, timeoutMs: number, expectedOrigin: string}} req
 * @returns {Promise<object|null>} the parsed mlay body, or null on a non-2xx
 */
async ({ catalog, timeoutMs, expectedOrigin }) => {
  if (location.origin !== expectedOrigin) {
    throw new Error('ksp: page navigated to ' + location.origin + ', expected ' + expectedOrigin);
  }
  const res = await fetch('/m_action/api/mlay/' + encodeURIComponent(catalog), {
    credentials: 'include',
    signal: AbortSignal.timeout(timeoutMs),
  });
  return res.ok ? await res.json() : null;
}
