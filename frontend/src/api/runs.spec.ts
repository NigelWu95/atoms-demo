import { afterEach, expect, it, vi } from 'vitest'
import { api } from './client'
import { decodeRun, submitRun, generationAvailability } from './runs'

afterEach(() => vi.unstubAllGlobals())
it('rejects malformed task states instead of claiming success', () => {
  expect(() => decodeRun({ id: 'id', status: 'FINISHED' })).toThrow()
  expect(() => decodeRun({ id: 'id', status: 'SUCCEEDED', resultVersionId: null })).toThrow()
})
it('sends the supplied intent key and exact current version baseline', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(Response.json({ runId: 'run-1', status: 'QUEUED' }, { status: 202 }))
  vi.stubGlobal('fetch', fetch)
  api.setCsrf({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' })
  const input = {
    prompt: '追加筛选',
    intent: 'MODIFY' as const,
    baseVersionId: 'version-1',
    expectedProjectRevision: 1,
  }
  await submitRun('project-1', input, 'same-intent')
  expect(fetch.mock.calls[0]?.[1].headers['Idempotency-Key']).toBe('same-intent')
  expect(JSON.parse(fetch.mock.calls[0]?.[1].body)).toEqual(input)
})
it('exposes only model readiness and request allowance', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      Response.json({
        configured: false,
        workerEnabled: true,
        remainingRequests: 0,
        model: 'deepseek-flash',
      }),
    ),
  )
  expect(await generationAvailability()).toEqual({
    configured: false,
    workerEnabled: true,
    remainingRequests: 0,
    model: 'deepseek-flash',
  })
})
