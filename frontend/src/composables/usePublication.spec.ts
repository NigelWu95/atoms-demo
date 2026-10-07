import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getProject, type Project } from '../api/projects'
import { listVersions } from '../api/artifacts'
import { getPreviewReport, type SavedReport } from '../api/previewReports'
import { getPublication, publish, unpublish, type Publication } from '../api/publication'
import { ApiFailure } from '../api/client'
import { usePublication } from './usePublication'

vi.mock('../api/projects', () => ({ getProject: vi.fn() }))
vi.mock('../api/artifacts', () => ({ listVersions: vi.fn() }))
vi.mock('../api/previewReports', () => ({ getPreviewReport: vi.fn() }))
vi.mock('../api/publication', () => ({
  getPublication: vi.fn(),
  publish: vi.fn(),
  unpublish: vi.fn(),
}))
const project: Project = {
  id: 'p',
  ownerId: 'o',
  name: '项目',
  currentVersionId: 'v1',
  projectRevision: 1,
  createdAt: '',
  updatedAt: '',
}
const empty: Publication = {
  status: 'UNPUBLISHED',
  slug: null,
  versionId: null,
  versionNo: null,
  revision: 0,
  publicPath: null,
  publicUrl: null,
}
const live: Publication = {
  ...empty,
  status: 'PUBLISHED',
  slug: 'a'.repeat(32),
  versionId: 'v1',
  versionNo: 1,
  revision: 1,
  publicPath: '/a/' + 'a'.repeat(32),
}
const report: SavedReport = {
  status: 'PASSED',
  reportId: 'r',
  checks: ['SCRIPT_PARSE', 'SDK_STARTUP', 'RUNTIME_ERRORS'],
  errors: [],
  viewport: { width: 800, height: 600 },
  createdAt: '',
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const source = ref(project)
  let state!: ReturnType<typeof usePublication>
  wrappers.push(
    mount(
      defineComponent({
        setup() {
          state = usePublication(
            () => source.value,
            () => true,
          )
          return () => null
        },
      }),
    ),
  )
  return { source, state }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getProject).mockResolvedValue(project)
  vi.mocked(listVersions).mockResolvedValue({
    items: [{ id: 'v1', projectId: 'p', versionNo: 1, createdAt: '' }],
    nextCursor: null,
  })
  vi.mocked(getPreviewReport).mockResolvedValue(report)
  vi.mocked(getPublication).mockResolvedValue(empty)
  vi.mocked(publish).mockResolvedValue(live)
  vi.mocked(unpublish).mockResolvedValue(undefined)
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
it('loads only when opened; latest passed report and explicit consent gate publication', async () => {
  const { state } = setup()
  expect(getPublication).not.toHaveBeenCalled()
  state.open.value = true
  await flushPromises()
  await state.submit()
  expect(publish).not.toHaveBeenCalled()
  state.confirmed.value = true
  expect(state.canPublish.value).toBe(true)
  vi.mocked(getPreviewReport).mockResolvedValue({ ...report, status: 'FAILED', errors: ['broken'] })
  await state.refresh()
  expect(state.canPublish.value).toBe(false)
})
it('retries an unknown publish with identical intent even if the editor advances', async () => {
  const { state, source } = setup()
  state.open.value = true
  await flushPromises()
  state.confirmed.value = true
  vi.mocked(publish).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断开', 0))
  await state.submit()
  const first = vi.mocked(publish).mock.calls[0]!
  source.value = { ...project, currentVersionId: 'v2', projectRevision: 2 }
  await flushPromises()
  vi.mocked(getPublication).mockResolvedValue(live)
  await state.retry()
  expect(vi.mocked(publish).mock.calls[1]).toEqual(first)
  expect(first[1]).toEqual({
    versionId: 'v1',
    expectedProjectRevision: 1,
    expectedPublicationRevision: 0,
  })
  expect(state.pending.value).toBeNull()
  expect(state.publication.value).toEqual(live)
})
it('reads current publication after acknowledgment instead of trusting an old replay', async () => {
  const { state } = setup()
  state.open.value = true
  await flushPromises()
  state.confirmed.value = true
  const latest = { ...live, versionId: 'v2', versionNo: 2, revision: 3 }
  vi.mocked(getPublication).mockResolvedValue(latest)
  await state.submit()
  expect(state.publication.value).toEqual(latest)
})
it('a failed read after successful mutation does not cause a second write', async () => {
  const { state } = setup()
  state.open.value = true
  await flushPromises()
  state.confirmed.value = true
  vi.mocked(getPublication).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断开'))
  await state.submit()
  expect(state.pending.value).toBeNull()
  await state.refresh()
  expect(publish).toHaveBeenCalledTimes(1)
})
it('keeps the original downline revision on uncertain retry', async () => {
  vi.mocked(getPublication).mockResolvedValue(live)
  const { state } = setup()
  state.open.value = true
  await flushPromises()
  vi.mocked(unpublish).mockRejectedValueOnce(new ApiFailure('INVALID_RESPONSE', '损坏'))
  await state.takeOffline()
  const first = vi.mocked(unpublish).mock.calls[0]!
  await state.retry()
  expect(vi.mocked(unpublish).mock.calls[1]).toEqual(first)
  expect(first[1]).toBe(1)
})
