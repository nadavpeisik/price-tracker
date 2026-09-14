import { describe, expect, it } from 'vitest'
import { readXsrfToken } from '@/lib/csrf'

/**
 * Fed the cookie string directly: jsdom refuses to store a `__Host-` cookie
 * on its http origin (the prefix demands Secure + https), so `document.cookie`
 * cannot carry the real name in tests.
 */
describe('readXsrfToken', () => {
  it('finds the token among other cookies and decodes it', () => {
    expect(readXsrfToken('a=1; __Host-XSRF-TOKEN=' + encodeURIComponent('tok=en') + '; b=2')).toBe('tok=en')
  })

  it('is null when the cookie is absent or empty', () => {
    expect(readXsrfToken('')).toBeNull()
    expect(readXsrfToken('a=1; XSRF-TOKEN=wrong-name')).toBeNull()
    expect(readXsrfToken('__Host-XSRF-TOKEN=')).toBeNull()
  })
})
