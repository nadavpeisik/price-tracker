import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api-error'
import { createQueryClient } from '@/lib/query-client'

describe('createQueryClient', () => {
  it('flips ["me"] to anonymous when any query fails with 401', async () => {
    const client = createQueryClient()
    client.setQueryData(['me'], { status: 'signed-in', user: { name: 'x' } })

    await client
      .fetchQuery({ queryKey: ['dashboard'], queryFn: () => Promise.reject(new ApiError(401, 'Unauthorized')) })
      .catch(() => undefined)

    expect(client.getQueryData(['me'])).toEqual({ status: 'anonymous' })
    // The failed entry is left in place (no removeQueries): inert, not refetching.
    expect(client.getQueryState(['dashboard'])?.status).toBe('error')
  })

  it('leaves ["me"] alone on other failures', async () => {
    const client = createQueryClient()
    client.setQueryData(['me'], { status: 'signed-in', user: { name: 'x' } })

    await client
      .fetchQuery({ queryKey: ['x'], queryFn: () => Promise.reject(new ApiError(403, 'Forbidden')) })
      .catch(() => undefined)

    expect(client.getQueryData(['me'])).toEqual({ status: 'signed-in', user: { name: 'x' } })
  })

  it('flips ["me"] to anonymous when a mutation fails with 401, like a query would (#250)', async () => {
    const client = createQueryClient()
    client.setQueryData(['me'], { status: 'signed-in', user: { name: 'x' } })

    await client
      .getMutationCache()
      .build(client, { mutationFn: () => Promise.reject(new ApiError(401, 'Unauthorized')) })
      .execute(undefined)
      .catch(() => undefined)

    expect(client.getQueryData(['me'])).toEqual({ status: 'anonymous' })
  })

  it('leaves ["me"] alone when a mutation fails otherwise', async () => {
    const client = createQueryClient()
    client.setQueryData(['me'], { status: 'signed-in', user: { name: 'x' } })

    await client
      .getMutationCache()
      .build(client, { mutationFn: () => Promise.reject(new ApiError(404, 'Not Found')) })
      .execute(undefined)
      .catch(() => undefined)

    expect(client.getQueryData(['me'])).toEqual({ status: 'signed-in', user: { name: 'x' } })
  })

  it('retry rule: 4xx is terminal at once, 5xx and network errors retry up to three times', () => {
    const retry = createQueryClient().getDefaultOptions().queries?.retry
    expect(typeof retry).toBe('function')
    const shouldRetry = retry as (count: number, error: Error) => boolean
    expect(shouldRetry(0, new ApiError(404, 'x'))).toBe(false)
    expect(shouldRetry(0, new ApiError(503, 'x'))).toBe(true)
    expect(shouldRetry(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetry(3, new ApiError(503, 'x'))).toBe(false)
  })
})
