/**
 * Full-page navigation behind one function (#248), so tests mock it instead
 * of fighting jsdom's non-navigating `window.location`. Used for the Auth0
 * logout hop, which must leave the SPA entirely. (Sign-in leaves through a
 * plain anchor, so it needs no helper.)
 */
export function navigateTo(url: string): void {
  window.location.assign(url)
}
