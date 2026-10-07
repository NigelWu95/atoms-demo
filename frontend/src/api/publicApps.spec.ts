import { afterEach, expect, it, vi } from 'vitest'
import { api } from './client'
import { getPublicApp, getPublicState, savePublicState } from './publicApps'

const slug = 'a'.repeat(32)
const versionId = '11111111-1111-4111-8111-111111111111'
const meta = {
  slug,
  versionId,
  versionNo: 1,
  title: '应用',
  summary: '说明',
  stateSchemaVersion: 1,
  initialState: {},
}
afterEach(() => vi.unstubAllGlobals())
it('binds LIVE state only to the host-selected slug and loaded version', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(Response.json(meta))
    .mockResolvedValueOnce(Response.json({ revision: 0, value: {}, stateSchemaVersion: 1 }))
    .mockResolvedValueOnce(
      Response.json({ revision: 1, value: { count: 3 }, stateSchemaVersion: 1 }),
    )
  vi.stubGlobal('fetch', fetch)
  api.setCsrf({ token: 'test', headerName: 'X-CSRF-TOKEN' })
  expect(await getPublicApp(slug)).toEqual(meta)
  await getPublicState(slug, versionId)
  await savePublicState(slug, versionId, { count: 3 }, 0, 'intent')
  expect(fetch.mock.calls[1]![0]).toBe(`/api/public/apps/${slug}/state?versionId=${versionId}`)
  expect(JSON.parse(fetch.mock.calls[2]![1].body)).toEqual({
    loadedVersionId: versionId,
    value: { count: 3 },
    expectedRevision: 0,
    stateSchemaVersion: 1,
  })
})
it.each([
  { slug: 'b'.repeat(32) },
  { versionId: 'bad' },
  { versionNo: 0 },
  { initialState: [] },
  { stateSchemaVersion: 2 },
])('rejects malformed public metadata %j', async (invalid) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...meta, ...invalid })))
  await expect(getPublicApp(slug)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
