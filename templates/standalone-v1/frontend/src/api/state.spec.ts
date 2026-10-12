import { afterEach, describe, expect, it, vi } from 'vitest'
import { createStateHandler } from './state'

const runtime = 'a'.repeat(64)
const key = '92278716-660e-40d5-831c-d23d99eb0d04:03578816-e262-41d8-b4fb-ec6e64716e76'
const csrf = { token: 'a'.repeat(96), headerName: 'X-CSRF-TOKEN' }
const saved = { value: { todos: ['事项'] }, revision: 1, stateSchemaVersion: 1 }
const save = {
  type: 'STATE_SAVE' as const,
  payload: { value: { todos: ['事项'] }, expectedRevision: 0 },
}
const reply = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
function setup() {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  const lifetime = new AbortController()
  return { fetcher, lifetime, handle: createStateHandler(runtime, lifetime.signal) }
}
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('standalone state transport', () => {
  it('reads real state and validates the full 256 KiB value without selecting a visitor', async () => {
    const s = setup()
    const large = {
      value: { x: 'a'.repeat(262136) },
      revision: Number.MAX_SAFE_INTEGER,
      stateSchemaVersion: 1,
    }
    s.fetcher.mockResolvedValueOnce(reply(large))
    expect(await s.handle({ type: 'STATE_GET', payload: {} }, key)).toEqual(large)
    expect(s.fetcher).toHaveBeenCalledWith(
      '/api/state',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        mode: 'same-origin',
        redirect: 'error',
      }),
    )
  })
  it('captures the original intent before awaiting CSRF and retries only on explicit invocation', async () => {
    const s = setup()
    s.fetcher.mockResolvedValueOnce(reply(csrf)).mockRejectedValueOnce(new Error('SECRET'))
    const original = structuredClone(save)
    const first = s.handle(original, key)
    original.payload.value.todos.push('later mutation')
    await expect(first).rejects.toMatchObject({ code: 'NETWORK_ERROR', writeUnknown: true })
    expect(s.fetcher).toHaveBeenCalledTimes(2)
    const sent = s.fetcher.mock.calls[1]![1]
    expect(JSON.parse(sent.body)).toEqual({
      loadedRuntimeId: runtime,
      ...save.payload,
      stateSchemaVersion: 1,
    })
    expect(sent.headers).toMatchObject({ 'Idempotency-Key': key, 'X-CSRF-TOKEN': csrf.token })
    s.fetcher.mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply(saved))
    expect(await s.handle(save, key)).toEqual(saved)
    expect(s.fetcher.mock.calls[3]![1].body).toBe(sent.body)
    expect(s.fetcher.mock.calls[3]![1].headers['Idempotency-Key']).toBe(key)
    expect(s.fetcher.mock.calls.map(([path]) => path)).not.toContain('/api/session')
  })
  it.each([500, 503, 200])('keeps an unconfirmed PUT outcome for status %s', async (status) => {
    const s = setup()
    s.fetcher
      .mockResolvedValueOnce(reply(csrf))
      .mockResolvedValueOnce(reply({ invalid: 'PRIVATE' }, status))
    await expect(s.handle(save, key)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      writeUnknown: true,
    })
    expect(s.fetcher).toHaveBeenCalledTimes(2)
  })

  it('validates the acknowledgement against the captured revision, not a later mutation', async () => {
    const s = setup()
    s.fetcher.mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply(saved))
    const original = structuredClone(save)
    const pending = s.handle(original, key)
    original.payload.expectedRevision = 40
    expect(await pending).toEqual(saved)
    expect(JSON.parse(s.fetcher.mock.calls[1]![1].body).expectedRevision).toBe(0)
  })
  it.each([
    [409, 'STATE_CONFLICT'],
    [409, 'STALE_APP_VERSION'],
    [409, 'IDEMPOTENCY_PAYLOAD_MISMATCH'],
    [422, 'INVALID_INPUT'],
    [403, 'CSRF_REJECTED'],
  ])('decodes confirmed %s/%s without overwriting state', async (status, code) => {
    const s = setup()
    s.fetcher.mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(
      reply(
        {
          error: { code, message: 'PRIVATE', retryable: false },
          requestId: '9972c85c-074e-48f8-a692-c2d4c20f2122',
        },
        Number(status),
      ),
    )
    await expect(s.handle(save, key)).rejects.toMatchObject({ code, writeUnknown: false })
    expect(s.fetcher).toHaveBeenCalledTimes(2)
  })
  it('does not attempt PUT when fresh CSRF cannot be obtained', async () => {
    const s = setup()
    s.fetcher.mockRejectedValueOnce(new Error('offline'))
    await expect(s.handle(save, key)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      writeUnknown: false,
    })
    expect(s.fetcher).toHaveBeenCalledOnce()
  })
  it('rejects invalid success envelopes, sizes and revisions without claiming they were saved', async () => {
    for (const bad of [
      { ...saved, extra: true },
      { ...saved, revision: -1 },
      { ...saved, revision: 2 ** 53 },
      { ...saved, stateSchemaVersion: 2 },
      { ...saved, value: [] },
      { ...saved, value: { x: 'a'.repeat(262137) } },
    ]) {
      const s = setup()
      s.fetcher.mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply(bad))
      await expect(s.handle(save, key)).rejects.toMatchObject({
        code: 'INVALID_RESPONSE',
        writeUnknown: true,
      })
    }
  })
  it('bounds the attempt, aborts on disposal, and never replays on reconnect', async () => {
    vi.useFakeTimers()
    const s = setup()
    s.fetcher.mockResolvedValueOnce(reply(csrf)).mockImplementation(
      (_path, init) =>
        new Promise((_resolve, reject) => {
          init.signal.addEventListener('abort', () => reject(init.signal.reason), { once: true })
        }),
    )
    const pending = expect(s.handle(save, key)).rejects.toMatchObject({
      code: 'TIMEOUT',
      writeUnknown: true,
    })
    await vi.advanceTimersByTimeAsync(8000)
    await pending
    expect(vi.getTimerCount()).toBe(0)
    s.lifetime.abort()
    await expect(s.handle(save, key)).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(s.fetcher).toHaveBeenCalledTimes(2)
  })
})
