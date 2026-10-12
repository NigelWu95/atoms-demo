import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'
import { decodeBuild, getBuild, listBuildEvents, submitBuild, cancelBuild } from './builds'

const id = 'a0000000-0000-4000-8000-000000000001'
const project = 'b0000000-0000-4000-8000-000000000001'
const version = 'c0000000-0000-4000-8000-000000000001'
const source = {
  id,
  projectId: project,
  versionId: version,
  status: 'QUEUED',
  stage: 'QUEUED',
  templateDigest: 'a'.repeat(64),
  buildPolicyVersion: 'native-v1',
  bundleId: null,
  archiveSha256: null,
  loadedRuntimeId: null,
  jarSha256: null,
  errorCode: null,
  errorMessage: null,
  createdAt: '2026-10-13T00:00:00Z',
  startedAt: null,
  finishedAt: null,
  lastEventSeq: 1,
}
afterEach(() => vi.unstubAllGlobals())
describe('engineering build protocol', () => {
  it('rejects incomplete success, unknown state, unsafe counters and private fields', () => {
    expect(decodeBuild(source).status).toBe('QUEUED')
    for (const changed of [
      { status: 'SUCCEEDED' },
      { status: 'READY' },
      { stage: 'SHELL' },
      { lastEventSeq: 1001 },
      { templateDigest: 'invalid' },
      { createdAt: 'tomorrow' },
      { executionId: id },
      { archiveSha256: 'b'.repeat(64) },
    ]) {
      expect(() => decodeBuild({ ...source, ...changed })).toThrow()
    }
  })
  it('uses stable intent keys and validates the exact accepted target', async () => {
    api.setCsrf({ token: 'test-only', headerName: 'X-CSRF-TOKEN' })
    const fetch = vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(Response.json({ buildId: id, projectId: project, versionId: version })),
      )
    vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    await submitBuild(project, version, 'same-intent', signal)
    await submitBuild(project, version, 'same-intent', signal)
    expect(fetch.mock.calls.map(([, request]) => request.headers['Idempotency-Key'])).toEqual([
      'same-intent',
      'same-intent',
    ])
    expect(fetch.mock.calls[0]![1].body).toBe(JSON.stringify({ versionId: version }))
    fetch.mockResolvedValueOnce(Response.json({ buildId: id, projectId: id, versionId: version }))
    await expect(submitBuild(project, version, 'same-intent', signal)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
    fetch.mockResolvedValueOnce(Response.json({ buildId: id }))
    await expect(cancelBuild(project, id, 'cancel-intent', signal)).resolves.toEqual({
      buildId: id,
    })
    expect(fetch.mock.calls.at(-1)![1].body).toBe('{}')
  })
  it('rejects detail target mismatch and non-monotonic event pages', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(Response.json({ ...source, projectId: id }))
    vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    await expect(getBuild(project, id, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    const event = {
      seq: 1,
      type: 'build.queued',
      status: 'QUEUED',
      stage: 'QUEUED',
      errorCode: null,
      createdAt: source.createdAt,
    }
    fetch.mockResolvedValueOnce(Response.json({ items: [event, event], nextCursor: 1 }))
    await expect(listBuildEvents(project, id, 0, signal)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
    fetch.mockResolvedValueOnce(Response.json({ items: [event], nextCursor: 1 }))
    await expect(listBuildEvents(project, id, 0, signal)).resolves.toMatchObject({ nextCursor: 1 })
  })
})
