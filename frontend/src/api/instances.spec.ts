import { expect, it, vi, afterEach } from 'vitest'
import { decodeInstance, listInstances, startInstance } from './instances'
import { api } from './client'
const id = 'a0000000-0000-4000-8000-000000000001'
const instance = {
  id,
  projectId: id,
  buildId: id,
  versionId: id,
  status: 'RUNNING',
  loadedRuntimeId: 'a'.repeat(64),
  errorCode: null,
  createdAt: '2026-10-13T00:00:00Z',
  startedAt: '2026-10-13T00:00:01Z',
  finishedAt: null,
}
afterEach(() => vi.restoreAllMocks())
it('accepts only coherent path-free instance descriptors', () => {
  expect(decodeInstance(instance)).toEqual(instance)
  for (const change of [
    { status: 'SUCCESS' },
    { id: '../other' },
    { loadedRuntimeId: null },
    { startedAt: null },
    { port: 8080 },
    { status: 'STOPPED', finishedAt: null },
  ])
    expect(() => decodeInstance({ ...instance, ...change })).toThrow()
})
it('validates project ownership and keeps the supplied intent key', async () => {
  const request = vi
    .spyOn(api, 'request')
    .mockImplementation(async (_path, decode) =>
      decode({ items: [{ ...instance, projectId: 'b0000000-0000-4000-8000-000000000001' }] }),
    )
  await expect(listInstances(id, new AbortController().signal)).rejects.toThrow()
  request.mockImplementation(async (_path, decode) => decode({ instanceId: id }))
  await startInstance(id, id, 'same-intent', new AbortController().signal)
  expect(request.mock.calls[1]?.[2]).toMatchObject({
    method: 'POST',
    idempotencyKey: 'same-intent',
    body: { buildId: id },
  })
})
