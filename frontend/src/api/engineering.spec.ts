import { afterEach, expect, it, vi } from 'vitest'
import { api, createApiClient } from './client'
import { downloadEngineering, engineeringFilename } from './engineering'

const version = {
  projectId: 'b07fd9c1-4ba0-490d-aac5-0f6ad9b0d884',
  id: '5ecea593-93e4-470a-aa62-3b2cb5d848bf',
  versionNo: 2,
  createdAt: '',
}
const zip = new Uint8Array([80, 75, 3, 4, 0, 1])
afterEach(() => vi.unstubAllGlobals())
it('requests an explicit version with same-origin cookies and a safe filename', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(
      new Response(zip, { headers: { 'Content-Type': 'application/zip', 'Content-Length': '6' } }),
    )
  vi.stubGlobal('fetch', fetch)
  const blob = await downloadEngineering(version, new AbortController().signal)
  expect(blob.size).toBe(6)
  expect(blob.type).toBe('application/zip')
  expect(fetch).toHaveBeenCalledWith(
    `/api/projects/${version.projectId}/versions/${version.id}/engineering.zip`,
    expect.objectContaining({ credentials: 'same-origin', cache: 'no-store', redirect: 'error' }),
  )
  expect(engineeringFilename(version)).toBe(`atoms-${version.projectId}-v2-engineering.zip`)
  expect(() => engineeringFilename({ ...version, projectId: '../escape' })).toThrow()
  expect(() => engineeringFilename({ ...version, versionNo: NaN })).toThrow()
})
it.each([401, 403, 404, 422, 500, 503])(
  'keeps structured %i errors, request IDs and session invalidation',
  async (status) => {
    const invalidated = vi.fn()
    api.onSessionFailure(invalidated)
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code: 'EXPORT_BUSY', message: '稍后重试' }, requestId: 'req-export' },
            { status },
          ),
        ),
    )
    await expect(downloadEngineering(version, new AbortController().signal)).rejects.toMatchObject({
      status,
      code: 'EXPORT_BUSY',
      requestId: 'req-export',
    })
    expect(invalidated).toHaveBeenCalledTimes(status === 401 || status === 403 ? 1 : 0)
  },
)
it.each([
  new Response('<html>error</html>', { headers: { 'Content-Type': 'text/html' } }),
  new Response('{}', { headers: { 'Content-Type': 'application/zip' } }),
  new Response(zip, { headers: { 'Content-Type': 'application/zip', 'Content-Length': '7' } }),
  new Response(zip, {
    headers: { 'Content-Type': 'application/zip', 'Content-Length': '9437185' },
  }),
])('refuses error pages, bad signatures, truncation and oversized bodies', async (response) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  await expect(downloadEngineering(version, new AbortController().signal)).rejects.toMatchObject({
    code: 'INVALID_RESPONSE',
  })
})
it('bounds streamed bytes even without a Content-Length and cancels the reader', async () => {
  const cancel = vi.fn()
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array(11))
    },
    cancel,
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream)))
  await expect(
    createApiClient().binary('/api/test', new AbortController().signal, 10),
  ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  expect(cancel).toHaveBeenCalledOnce()
})
it('rejects an aborted result even if the transport ignores cancellation', async () => {
  const controller = new AbortController()
  controller.abort()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(zip)))
  await expect(downloadEngineering(version, controller.signal)).rejects.toThrow()
  expect(fetch).not.toHaveBeenCalled()
})
