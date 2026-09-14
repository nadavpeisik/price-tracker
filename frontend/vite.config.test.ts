import { describe, expect, it } from 'vitest'
import config from './vite.config'

/**
 * The dev proxy must preserve the browser's Host header (#248): the BFF
 * derives its OAuth redirect URIs from it, and the Auth0 app registers the
 * :5173 callback. Vite's string shorthand (`'/bff': 'http://…'`) expands to
 * `changeOrigin: true` and would send the login round trip to :8082.
 */
describe('vite dev proxy', () => {
  it('forwards /bff to the BFF without rewriting Host', async () => {
    const resolved = await (typeof config === 'function' ? config({ mode: 'development', command: 'serve' }) : config)
    const proxy = resolved.server?.proxy?.['/bff']
    expect(proxy).toEqual({ target: 'http://localhost:8082', changeOrigin: false })
    expect(resolved.server?.proxy?.['/api']).toBeUndefined()
  })
})
