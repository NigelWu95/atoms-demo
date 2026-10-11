import { afterEach, describe, expect, it, vi } from 'vitest'
import { checkHealth, HealthFailure } from './health'

const signal = new AbortController().signal
function respond(body: string, status = 200, type = 'application/json') {
  const fetcher = vi
    .fn()
    .mockResolvedValue(new Response(body, { status, headers: { 'Content-Type': type } }))
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}
afterEach(() => vi.unstubAllGlobals())

describe('read-only service readiness', () => {
  it('checks the fixed same-origin endpoint without creating a visitor', async () => {
    const fetcher = respond('{"status":"UP"}')
    await checkHealth(signal)
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/health', {
      signal,
      method: 'GET',
      mode: 'same-origin',
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      headers: { Accept: 'application/json' },
    })
  })

  it.each([
    '{}',
    '{"status":"DOWN"}',
    '{"status":"UP","extra":true}',
    'null',
    '[]',
    '<html>bad</html>',
  ])('rejects malformed health data: %s', async (body) => {
    respond(body)
    await expect(checkHealth(signal)).rejects.toMatchObject({ kind: 'invalid' })
  })

  it('rejects a successful HTML fallback, oversized response and unexpected status', async () => {
    respond('{"status":"UP"}', 200, 'text/html')
    await expect(checkHealth(signal)).rejects.toMatchObject({ kind: 'invalid' })
    respond(' '.repeat(1025))
    await expect(checkHealth(signal)).rejects.toMatchObject({ kind: 'invalid' })
    respond('{"status":"UP"}', 201)
    await expect(checkHealth(signal)).rejects.toMatchObject({ kind: 'invalid' })
  })

  it.each([
    [503, 'unavailable'],
    [500, 'unavailable'],
    [403, 'forbidden'],
  ])('maps HTTP %s without exposing upstream text', async (status, kind) => {
    respond('PRIVATE_SERVER_TEXT', Number(status))
    await expect(checkHealth(signal)).rejects.toMatchObject({ kind })
    await expect(checkHealth(signal)).rejects.not.toThrow('PRIVATE_SERVER_TEXT')
  })

  it('distinguishes unavailable network from a timed-out request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('PRIVATE_NETWORK_TEXT')))
    await expect(checkHealth(signal)).rejects.toMatchObject({ kind: 'offline' })
    const controller = new AbortController()
    controller.abort(new DOMException('Timed out', 'TimeoutError'))
    await expect(checkHealth(controller.signal)).rejects.toMatchObject({ kind: 'timeout' })
    expect(new HealthFailure('offline').message).not.toContain('PRIVATE')
  })
})
