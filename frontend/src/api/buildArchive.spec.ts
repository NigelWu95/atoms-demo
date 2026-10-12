import { afterEach, expect, it, vi } from 'vitest'
import { downloadBuildArchive, buildArchiveFilename } from './buildArchive'
const target = {
  id: 'a0000000-0000-4000-8000-000000000001',
  projectId: 'b0000000-0000-4000-8000-000000000001',
}
afterEach(() => vi.unstubAllGlobals())
it('downloads by build ID, with bounded ZIP validation and no version reassembly', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        new Response(new Uint8Array([80, 75, 3, 4]), {
          headers: { 'Content-Type': 'application/zip' },
        }),
      ),
  )
  expect((await downloadBuildArchive(target, new AbortController().signal)).size).toBe(4)
  expect(fetch).toHaveBeenCalledWith(
    `/api/projects/${target.projectId}/builds/${target.id}/engineering.zip`,
    expect.objectContaining({ redirect: 'error', cache: 'no-store' }),
  )
  expect(buildArchiveFilename(target)).toBe(`atoms-build-${target.id}-engineering.zip`)
  expect(() => buildArchiveFilename({ ...target, id: '../invalid' })).toThrow()
})
it('refuses error pages and discarded responses', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(new Response('{}', { headers: { 'Content-Type': 'application/zip' } })),
  )
  await expect(downloadBuildArchive(target, new AbortController().signal)).rejects.toMatchObject({
    code: 'INVALID_RESPONSE',
  })
  const stopped = new AbortController()
  stopped.abort()
  await expect(downloadBuildArchive(target, stopped.signal)).rejects.toThrow()
  expect(fetch).toHaveBeenCalledTimes(1)
})
