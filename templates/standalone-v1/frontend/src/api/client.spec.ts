import { afterEach, describe, expect, it, vi } from 'vitest'
import { initializeApp } from './client'

const id = '9972c85c-074e-48f8-a692-c2d4c20f2122'
const csrf = { token: 'a'.repeat(96), headerName: 'X-CSRF-TOKEN' }
const app = {
  title: '计数器',
  summary: '独立应用',
  runtime: 'web-static-v1',
  stateSchemaVersion: 1,
  loadedRuntimeId: 'a'.repeat(64),
}
const signal = new AbortController().signal
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function connect() {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(response(csrf))
    .mockResolvedValueOnce(response({ userId: id, isGuest: true }))
    .mockResolvedValueOnce(response({ ...csrf, token: 'b'.repeat(96) }))
    .mockResolvedValueOnce(response(app))
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}
afterEach(() => vi.unstubAllGlobals())

describe('standalone session initialization', () => {
  it('initializes with CSRF then refreshes the rotated token before loading metadata', async () => {
    const fetcher = connect()
    expect(await initializeApp(signal)).toEqual(app)
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      '/api/csrf',
      '/api/session',
      '/api/csrf',
      '/api/app',
    ])
    for (const [, init] of fetcher.mock.calls)
      expect(init).toMatchObject({
        credentials: 'same-origin',
        mode: 'same-origin',
        redirect: 'error',
        cache: 'no-store',
        signal,
      })
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      body: '{}',
      headers: { 'X-CSRF-TOKEN': csrf.token, 'Content-Type': 'application/json' },
    })
    expect(fetcher.mock.calls[3]?.[1].headers).not.toHaveProperty('X-CSRF-TOKEN')
  })

  it.each([
    [401, 'SESSION_REQUIRED'],
    [403, 'CSRF_REJECTED'],
    [403, 'FORBIDDEN'],
    [503, 'SERVICE_UNAVAILABLE'],
  ])('reports %s/%s without retrying or leaking upstream text', async (status, code) => {
    const fetcher = vi.fn().mockResolvedValue(
      response(
        {
          error: { code, message: 'PRIVATE_SERVER_TEXT', retryable: Number(status) >= 500 },
          requestId: id,
        },
        Number(status),
      ),
    )
    vi.stubGlobal('fetch', fetcher)
    await expect(initializeApp(signal)).rejects.toMatchObject({ code, status, requestId: id })
    expect(fetcher).toHaveBeenCalledOnce()
    await expect(initializeApp(signal)).rejects.not.toThrow('PRIVATE_SERVER_TEXT')
  })

  it('never automatically retries a session POST with an unknown result', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(csrf))
      .mockRejectedValueOnce(new Error('PRIVATE_NETWORK_TEXT'))
    vi.stubGlobal('fetch', fetcher)
    await expect(initializeApp(signal)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('rejects invalid header authority, session identities and app metadata', async () => {
    for (const badCsrf of [
      { ...csrf, headerName: 'Authorization' },
      { ...csrf, token: 'injected\r\nheader' },
    ]) {
      const fetcher = vi.fn().mockResolvedValue(response(badCsrf))
      vi.stubGlobal('fetch', fetcher)
      await expect(initializeApp(signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
      expect(fetcher).toHaveBeenCalledOnce()
    }
    for (const badIdentity of [
      { userId: '../user', isGuest: true },
      { userId: id, isGuest: false },
    ]) {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValueOnce(response(csrf)).mockResolvedValueOnce(response(badIdentity)),
      )
      await expect(initializeApp(signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    }
    for (const change of [
      { runtime: 'arbitrary-code' },
      { stateSchemaVersion: 2 },
      { loadedRuntimeId: '../version' },
      { title: '' },
      { title: 'a'.repeat(121) },
      { summary: 'a'.repeat(2001) },
      { extra: true },
    ]) {
      const fetcher = connect()
      fetcher
        .mockReset()
        .mockResolvedValueOnce(response(csrf))
        .mockResolvedValueOnce(response({ userId: id, isGuest: true }))
        .mockResolvedValueOnce(response(csrf))
        .mockResolvedValueOnce(response({ ...app, ...change }))
      await expect(initializeApp(signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    }
  })

  it('treats redirects, HTML, oversized JSON and wrong success codes as failures', async () => {
    for (const reply of [
      new Response('<html>oops</html>'),
      response(csrf, 201),
      response({ token: 'x'.repeat(20000), headerName: 'X-CSRF-TOKEN' }),
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply))
      await expect(initializeApp(signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    }
  })

  it('reports cancellation or timeout without another request', async () => {
    const controller = new AbortController()
    controller.abort(new DOMException('Deadline', 'TimeoutError'))
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    await expect(initializeApp(controller.signal)).rejects.toMatchObject({ code: 'TIMEOUT' })
    expect(fetcher).not.toHaveBeenCalled()
  })
})
