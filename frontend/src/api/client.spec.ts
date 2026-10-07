import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiFailure, createApiClient } from './client'

afterEach(() => vi.unstubAllGlobals())
const decode = (value: unknown) => value

describe('HTTP boundary', () => {
  it('invalidates the session on an HTML 401 without disguising its HTTP status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('<html>expired</html>', { status: 401 })),
    )
    const client = createApiClient()
    const invalidated = vi.fn()
    client.onSessionFailure(invalidated)
    await expect(client.request('/api/me', decode)).rejects.toMatchObject({
      status: 401,
      code: 'HTTP_ERROR',
    })
    expect(invalidated).toHaveBeenCalledTimes(1)
  })
  it('includes same-origin cookies and CSRF, preserving the caller intent key', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(Response.json({ ok: true })))
    vi.stubGlobal('fetch', fetch)
    const client = createApiClient()
    client.setCsrf({ token: 'test-only-token', headerName: 'X-CSRF-TOKEN' })
    const options = { method: 'POST' as const, body: { name: '项目' }, idempotencyKey: 'intent-1' }
    await client.request('/api/projects', decode, options)
    await client.request('/api/projects', decode, options)
    expect(fetch).toHaveBeenCalledTimes(2)
    for (const [, init] of fetch.mock.calls) {
      expect(init.credentials).toBe('same-origin')
      expect(init.headers['X-CSRF-TOKEN']).toBe('test-only-token')
      expect(init.headers['Idempotency-Key']).toBe('intent-1')
    }
  })

  it.each([401, 403, 422, 503])(
    'preserves structured %i errors and never retries a mutation',
    async (status) => {
      const fetch = vi.fn().mockResolvedValue(
        Response.json(
          {
            error: { code: 'TEST_FAILURE', message: '请求失败', retryable: status === 503 },
            requestId: 'req-test',
          },
          { status },
        ),
      )
      vi.stubGlobal('fetch', fetch)
      const client = createApiClient()
      client.setCsrf({ token: 'test-only-token', headerName: 'X-CSRF-TOKEN' })
      await expect(
        client.request('/api/projects', decode, { method: 'POST', body: {} }),
      ).rejects.toMatchObject({ status, code: 'TEST_FAILURE', requestId: 'req-test' })
      expect(fetch).toHaveBeenCalledTimes(1)
    },
  )

  it('reports transport failure as an unknown result, without retrying', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('offline'))
    vi.stubGlobal('fetch', fetch)
    await expect(createApiClient().request('/api/me', decode)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('rejects invalid JSON and prevents writes without CSRF', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>not API</html>')))
    const client = createApiClient()
    await expect(client.request('/api/me', decode)).rejects.toBeInstanceOf(ApiFailure)
    await expect(
      client.request('/api/projects', decode, { method: 'POST', body: {} }),
    ).rejects.toMatchObject({ code: 'SESSION_REQUIRED' })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
