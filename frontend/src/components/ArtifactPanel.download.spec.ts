import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ArtifactPanel from './ArtifactPanel.vue'
import ApplicationRunPanel from './ApplicationRunPanel.vue'
import InstancePreviewFrame from './InstancePreviewFrame.vue'
import { getVersion, listVersions, type Version } from '../api/artifacts'
import { downloadBuildArchive } from '../api/buildArchive'
import type { AppInstance } from '../api/instances'
import type { Project } from '../api/projects'
import { useSessionStore } from '../stores/session'

vi.mock('../api/artifacts', () => ({ getVersion: vi.fn(), listVersions: vi.fn() }))
vi.mock('../api/buildArchive', async (original) => ({
  ...(await original<typeof import('../api/buildArchive')>()),
  downloadBuildArchive: vi.fn(),
}))
const project: Project = {
  id: 'a0000000-0000-4000-8000-000000000001',
  name: '待办',
  ownerId: 'owner-a',
  currentVersionId: 'version-a',
  projectRevision: 1,
  createdAt: '2026-10-14T00:00:00Z',
  updatedAt: '2026-10-14T00:00:00Z',
}
const version: Version = {
  id: 'version-a',
  projectId: project.id,
  versionNo: 1,
  createdAt: project.createdAt,
  artifact: {
    formatVersion: 1,
    manifest: {
      title: '待办',
      summary: '持久化',
      runtime: 'web-static-v1',
      stateSchemaVersion: 1,
      initialState: {},
    },
    files: { 'view.html': '', 'styles.css': '', 'main.js': '' },
  },
}
const runtime: AppInstance = {
  id: 'execution-a',
  projectId: project.id,
  versionId: version.id,
  buildId: 'b0000000-0000-4000-8000-000000000001',
  status: 'RUNNING',
  loadedRuntimeId: 'a'.repeat(64),
  errorCode: null,
  createdAt: project.createdAt,
  startedAt: project.createdAt,
  finishedAt: null,
}
const wrappers: ReturnType<typeof mount>[] = []
let anchorClick: ReturnType<typeof vi.spyOn>
function open() {
  const pinia = createPinia(),
    session = useSessionStore(pinia)
  session.state = 'ready'
  session.identity = { userId: project.ownerId, displayName: '访客', isGuest: true }
  const wrapper = mount(ArtifactPanel, {
    props: { project },
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: true,
        EngineeringDownload: true,
        ApplicationRunPanel: { template: '<div><slot /></div>' },
        InstancePreviewFrame: true,
        PreviewFrame: true,
        SourceViewer: true,
        RestorationPanel: true,
      },
    },
  })
  wrappers.push(wrapper)
  return { wrapper, session }
}
type Panel = ReturnType<typeof open>['wrapper']
const download = (wrapper: Panel) => wrapper.get('[aria-label="预览工程下载"] button')
async function start(wrapper: Panel) {
  await flushPromises()
  wrapper.findComponent(ApplicationRunPanel).vm.$emit('running', runtime)
  await flushPromises()
}
async function ready(wrapper: Panel) {
  await start(wrapper)
  wrapper.findComponent(InstancePreviewFrame).vm.$emit('availability', runtime.id, true)
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getVersion).mockResolvedValue(version)
  vi.mocked(listVersions).mockResolvedValue({ items: [version], nextCursor: null })
  vi.mocked(downloadBuildArchive).mockResolvedValue(new Blob(['zip']))
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:preview')
      static revokeObjectURL = vi.fn()
    },
  )
  anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
it('keeps the existing version export directly visible, outside any disclosure', async () => {
  const { wrapper } = open()
  await flushPromises()
  expect(wrapper.get('engineering-download-stub').element.closest('details')).toBeNull()
})
it('downloads running build A, never the newly selected build B or a version re-export', async () => {
  const { wrapper } = open()
  await ready(wrapper)
  wrapper
    .findComponent(ApplicationRunPanel)
    .vm.$emit('selected', { id: 'build-b', status: 'SUCCEEDED' })
  await flushPromises()
  await download(wrapper).trigger('click')
  await flushPromises()
  expect(downloadBuildArchive).toHaveBeenCalledWith(
    { id: runtime.buildId, projectId: project.id },
    expect.any(AbortSignal),
  )
  expect(anchorClick).toHaveBeenCalledOnce()
})
it('requires the matching live preview handshake, and disables on runtime error', async () => {
  const { wrapper } = open()
  await flushPromises()
  expect(download(wrapper).attributes('disabled')).toBeDefined()
  await start(wrapper)
  expect(download(wrapper).attributes('disabled')).toBeDefined()
  const frame = wrapper.findComponent(InstancePreviewFrame)
  frame.vm.$emit('availability', 'old-execution', true)
  await flushPromises()
  expect(download(wrapper).attributes('disabled')).toBeDefined()
  frame.vm.$emit('availability', runtime.id, true)
  await flushPromises()
  expect(download(wrapper).attributes('disabled')).toBeUndefined()
  frame.vm.$emit('availability', runtime.id, false)
  await flushPromises()
  expect(download(wrapper).attributes('disabled')).toBeDefined()
})
it.each([
  { status: 'STOPPED' as const },
  { projectId: 'another-project' },
  { versionId: 'another-version' },
])('rejects a runtime outside the selected live context: %o', async (mismatch) => {
  const { wrapper } = open()
  await flushPromises()
  wrapper.findComponent(ApplicationRunPanel).vm.$emit('running', { ...runtime, ...mismatch })
  await flushPromises()
  expect(wrapper.findComponent(InstancePreviewFrame).exists()).toBe(false)
  expect(download(wrapper).attributes('disabled')).toBeDefined()
})
it('requires a new handshake after switching away and back to the same process', async () => {
  const { wrapper } = open()
  await ready(wrapper)
  const modes = wrapper.get('[aria-label="预览运行环境"]').findAll('button')
  await modes[0]!.trigger('click')
  await modes[1]!.trigger('click')
  expect(download(wrapper).attributes('disabled')).toBeDefined()
  wrapper.findComponent(InstancePreviewFrame).vm.$emit('availability', runtime.id, true)
  await flushPromises()
  expect(download(wrapper).attributes('disabled')).toBeUndefined()
})
it.each(['stop', 'shared', 'version', 'project', 'owner'] as const)(
  'invalidates and aborts a preview download on %s, discarding late bytes',
  async (change) => {
    let resolve!: (blob: Blob) => void
    vi.mocked(downloadBuildArchive).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const { wrapper, session } = open()
    await ready(wrapper)
    await download(wrapper).trigger('click')
    const signal = vi.mocked(downloadBuildArchive).mock.calls[0]![1]
    if (change === 'stop') wrapper.findComponent(ApplicationRunPanel).vm.$emit('running', null)
    if (change === 'shared')
      await wrapper.get('[aria-label="预览运行环境"] button').trigger('click')
    if (change === 'version')
      await wrapper.setProps({ project: { ...project, currentVersionId: 'version-b' } })
    if (change === 'project') await wrapper.setProps({ project: { ...project, id: 'project-b' } })
    if (change === 'owner')
      session.identity = { userId: 'owner-b', displayName: '访客', isGuest: true }
    await flushPromises()
    expect(download(wrapper).attributes('disabled')).toBeDefined()
    expect(signal.aborted).toBe(true)
    resolve(new Blob(['late']))
    await flushPromises()
    expect(anchorClick).not.toHaveBeenCalled()
  },
)
