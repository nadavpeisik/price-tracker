/**
 * CSRF double-submit (#247/#248): the BFF sets a JS-readable
 * `__Host-XSRF-TOKEN` cookie and expects its value back in an `X-XSRF-TOKEN`
 * header on every mutation. It is reissued on every response and cleared by
 * login, so it is read per request rather than cached.
 */
const COOKIE_NAME = '__Host-XSRF-TOKEN'

export function readXsrfToken(cookie: string = document.cookie): string | null {
  for (const part of cookie.split(';')) {
    const [name, ...rest] = part.trim().split('=')
    if (name === COOKIE_NAME) {
      const value = rest.join('=')
      // No try/catch around the decode (raised three times in review): Spring writes this cookie
      // and its value is a UUID, so there is no producer for a malformed percent-escape.
      return value ? decodeURIComponent(value) : null
    }
  }
  return null
}
