import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiFailure, createApiClient } from './client'

afterEach(() => vi.unstubAllGlobals())
const decode = (value: unknown) => value

describe('HTTP boundary', () => {
  it('aborts all old identity requests and ignores late results and failures', async () => {
    const finishes: ((response: Response) => void)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => finishes.push(resolve))),
    )
    const client = createApiClient(),
      failure = vi.fn()
    client.onSessionFailure(failure)
    const old = client.request('/api/projects', decode)
    const denied = client.request('/api/me', decode)
    client.resetScope()
    finishes[0]!(Response.json({ secret: 'old project' }))
    finishes[1]!(Response.json({}, { status: 401 }))
    await expect(old).rejects.toMatchObject({ name: 'AbortError' })
    await expect(denied).rejects.toMatchObject({ name: 'AbortError' })
    expect(failure).not.toHaveBeenCalled()
  })
  it('does not apply an old session error after its caller aborts', async () => {
    let finish!: (response: Response) => void
    const fetch = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve
          }),
      )
      .mockResolvedValue(Response.json({ ok: true }))
    vi.stubGlobal('fetch', fetch)
    const client = createApiClient()
    const invalidated = vi.fn()
    client.onSessionFailure(invalidated)
    const controller = new AbortController()
    const request = client.request('/api/old-project', decode, { signal: controller.signal })
    controller.abort()
    client.setCsrf({ token: 'new-test-token', headerName: 'X-CSRF-TOKEN' })
    finish(Response.json({ error: { code: 'SESSION_REQUIRED' } }, { status: 403 }))
    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
    expect(invalidated).not.toHaveBeenCalled()
    await client.request('/api/new-project', decode, { method: 'POST', body: {} })
    expect(fetch.mock.calls[1]![1].headers['X-CSRF-TOKEN']).toBe('new-test-token')
  })
  it('refuses an already-aborted JSON request without starting network work', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    controller.abort()
    await expect(
      createApiClient().request('/api/builds', decode, { signal: controller.signal }),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('decodes an empty 204 DELETE response without weakening JSON response validation', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetch)
    const client = createApiClient()
    client.setCsrf({ token: 'test-only-token', headerName: 'X-CSRF-TOKEN' })
    await expect(
      client.request('/api/projects/p/publication', decode, {
        method: 'DELETE',
        idempotencyKey: 'offline',
      }),
    ).resolves.toBeUndefined()
    expect(fetch.mock.calls[0]![1].headers['Idempotency-Key']).toBe('offline')
  })
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
