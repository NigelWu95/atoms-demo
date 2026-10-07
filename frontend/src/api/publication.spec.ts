import { afterEach, expect, it, vi } from 'vitest'
import { api } from './client'
import { getPublication, publish, unpublish } from './publication'

const slug = 'a'.repeat(32)
const published = {
  slug,
  versionId: '11111111-1111-4111-8111-111111111111',
  versionNo: 1,
  status: 'PUBLISHED',
  revision: 1,
  publicPath: `/a/${slug}`,
  publicUrl: null,
}
afterEach(() => vi.unstubAllGlobals())
it('accepts HTTP publication links only on the current HTTP origin', async () => {
  vi.stubGlobal('location', new URL('http://198.51.100.1:8443/'))
  const result = { ...published, publicUrl: 'http://198.51.100.1:8443' + published.publicPath }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(result)))
  expect(await getPublication('p')).toEqual(result)
})
it.each(['http://other.example', 'http://198.51.100.1:80'])(
  'rejects a different HTTP origin: %s',
  async (origin) => {
    vi.stubGlobal('location', new URL('http://198.51.100.1:8443/'))
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        Response.json({
          ...published,
          publicUrl: origin + published.publicPath,
        }),
      ),
    )
    await expect(getPublication('p')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  },
)
it('rejects a remote HTTP downgrade from an HTTPS page', async () => {
  vi.stubGlobal('location', new URL('https://198.51.100.1:8443/'))
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      Response.json({
        ...published,
        publicUrl: 'http://198.51.100.1:8443' + published.publicPath,
      }),
    ),
  )
  await expect(getPublication('p')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
it('binds publish/unpublish to revisions and caller intents', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(Response.json(published))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetch)
  api.setCsrf({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' })
  const input = {
    versionId: published.versionId,
    expectedProjectRevision: 1,
    expectedPublicationRevision: 0,
  }
  expect(await publish('p', input, 'publish')).toEqual(published)
  await unpublish('p', 1, 'offline')
  expect(fetch.mock.calls[0]![1].body).toBe(JSON.stringify(input))
  expect(fetch.mock.calls[1]![0]).toBe('/api/projects/p/publication?expectedPublicationRevision=1')
  expect(fetch.mock.calls[1]![1].headers['Idempotency-Key']).toBe('offline')
})
it.each([
  { publicUrl: 'javascript:alert(1)' },
  { publicPath: '//evil.example' },
  { revision: -1 },
  { versionNo: 0 },
  { status: 'PUBLISHED', versionId: null },
  { slug: '../../admin' },
  { publicUrl: 'https://example.test/a/wrong' },
  { publicUrl: 'https://user:secret@example.test/a/' + slug },
])('rejects inconsistent or unsafe publication responses: %j', async (invalid) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...published, ...invalid })))
  await expect(getPublication('p')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
