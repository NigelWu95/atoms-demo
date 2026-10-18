import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ApplicationRunPanel from './ApplicationRunPanel.vue'
import { useSessionStore } from '../stores/session'
import {
  getBuild,
  listBuilds,
  listBuildEvents,
  submitBuild,
  type EngineeringBuild,
} from '../api/builds'
import { listInstances, startInstance, stopInstance, type AppInstance } from '../api/instances'
import { ApiFailure } from '../api/client'

vi.mock('../api/builds', async (original) => ({
  ...(await original<typeof import('../api/builds')>()),
  getBuild: vi.fn(),
  listBuilds: vi.fn(),
  listBuildEvents: vi.fn(),
  submitBuild: vi.fn(),
  cancelBuild: vi.fn(),
}))
vi.mock('../api/instances', async (original) => ({
  ...(await original<typeof import('../api/instances')>()),
  listInstances: vi.fn(),
  startInstance: vi.fn(),
  stopInstance: vi.fn(),
}))
const id = 'a0000000-0000-4000-8000-000000000001'
const other = 'b0000000-0000-4000-8000-000000000001'
const date = '2026-10-14T00:00:00Z'
const version = { id, projectId: id, versionNo: 1, createdAt: date }
const queued: EngineeringBuild = {
  id,
  projectId: id,
  versionId: id,
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
  createdAt: date,
  startedAt: null,
  finishedAt: null,
  lastEventSeq: 1,
}
const done: EngineeringBuild = {
  ...queued,
  status: 'SUCCEEDED',
  stage: 'COMPLETE',
  bundleId: 'b'.repeat(64),
  archiveSha256: 'c'.repeat(64),
  loadedRuntimeId: 'd'.repeat(64),
  jarSha256: 'e'.repeat(64),
  startedAt: date,
  finishedAt: date,
}
const runtime: AppInstance = {
  id,
  projectId: id,
  versionId: id,
  buildId: id,
  status: 'RUNNING',
  loadedRuntimeId: 'd'.repeat(64),
  errorCode: null,
  createdAt: date,
  startedAt: date,
  finishedAt: null,
}
const wrappers: ReturnType<typeof mount>[] = []
function open() {
  const pinia = createPinia(),
    session = useSessionStore(pinia)
  session.state = 'ready'
  session.identity = { userId: id, displayName: '访客', isGuest: true }
  const wrapper = mount(ApplicationRunPanel, {
    props: { projectId: id, ownerId: id, currentVersionId: id, version, loading: false },
    global: { plugins: [pinia], stubs: { BuildArchiveDownload: true } },
    slots: {
      default: '<div data-preview-content>应用画面</div>',
      'preview-controls': '<button data-preview-mode>切换预览环境</button>',
    },
  })
  wrappers.push(wrapper)
  return { wrapper, session }
}
const run = (wrapper: ReturnType<typeof open>['wrapper']) =>
  wrapper.get('[data-action="run-application"]')
it('places the preview slot between the primary action and advanced controls without extra controllers', async () => {
  const { wrapper } = open()
  await flushPromises()
  const primary = wrapper.get('[aria-label="运行应用流程"]').element
  const preview = wrapper.get('[data-preview-content]').element
  const advanced = wrapper.get('[aria-label="高级构建与进程管理"]').element
  expect(primary.contains(wrapper.get('[data-preview-mode]').element)).toBe(true)
  expect(primary.compareDocumentPosition(preview) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(preview.compareDocumentPosition(advanced) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(listBuilds).toHaveBeenCalledTimes(1)
  expect(listInstances).toHaveBeenCalledTimes(1)
  expect(submitBuild).not.toHaveBeenCalled()
  expect(startInstance).not.toHaveBeenCalled()
})
async function finishBuild() {
  vi.mocked(getBuild).mockResolvedValue(done)
  await vi.advanceTimersByTimeAsync(2000)
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  sessionStorage.clear()
  vi.mocked(listBuilds).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(getBuild).mockResolvedValue(queued)
  vi.mocked(listBuildEvents).mockResolvedValue({ items: [], nextCursor: 0 })
  vi.mocked(submitBuild).mockResolvedValue({ buildId: id, projectId: id, versionId: id })
  vi.mocked(listInstances).mockResolvedValue([])
  vi.mocked(startInstance).mockImplementation(async () => {
    vi.mocked(listInstances).mockResolvedValue([runtime])
    return { instanceId: id }
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})
it('opens read-only; one explicit click builds, starts once and requests the real preview', async () => {
  const { wrapper } = open()
  await flushPromises()
  expect(listBuilds).toHaveBeenCalledTimes(1)
  expect(listInstances).toHaveBeenCalledTimes(1)
  expect(submitBuild).not.toHaveBeenCalled()
  expect(startInstance).not.toHaveBeenCalled()
  await run(wrapper).trigger('click')
  await run(wrapper).trigger('click')
  await flushPromises()
  expect(submitBuild).toHaveBeenCalledTimes(1)
  expect(startInstance).not.toHaveBeenCalled()
  await finishBuild()
  expect(startInstance).toHaveBeenCalledTimes(1)
  expect(startInstance).toHaveBeenCalledWith(id, id, expect.any(String), expect.any(AbortSignal))
  expect(wrapper.emitted('preview')).toHaveLength(1)
  expect(wrapper.emitted('running')?.at(-1)).toEqual([runtime])
  await vi.advanceTimersByTimeAsync(4000)
  expect(startInstance).toHaveBeenCalledTimes(1)
})
it('reuses a successful build without submitting another build', async () => {
  vi.mocked(listBuilds).mockResolvedValue({ items: [done], nextCursor: null })
  vi.mocked(getBuild).mockResolvedValue(done)
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  expect(submitBuild).not.toHaveBeenCalled()
  expect(startInstance).toHaveBeenCalledTimes(1)
})
it('adopts an active build only after explicit consent, without duplicate submission', async () => {
  vi.mocked(listBuilds).mockResolvedValue({ items: [queued], nextCursor: null })
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  await finishBuild()
  expect(submitBuild).not.toHaveBeenCalled()
  expect(startInstance).toHaveBeenCalledTimes(1)
})
it('shows an already running current version without rebuilding or restarting it', async () => {
  vi.mocked(listInstances).mockResolvedValue([runtime])
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  expect(wrapper.emitted('preview')).toHaveLength(1)
  expect(startInstance).not.toHaveBeenCalled()
  expect(submitBuild).not.toHaveBeenCalled()
})
it('requires explicit stop of an older version instead of replacing it', async () => {
  vi.mocked(listInstances).mockResolvedValue([{ ...runtime, versionId: other }])
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  expect(wrapper.get('[aria-label="运行应用流程"]').text()).toContain('请先停止旧进程')
  expect(submitBuild).not.toHaveBeenCalled()
  expect(startInstance).not.toHaveBeenCalled()
  expect(stopInstance).not.toHaveBeenCalled()
})
it.each(['FAILED', 'CANCELLED', 'INTERRUPTED'] as const)(
  'does not start a %s build',
  async (status) => {
    const { wrapper } = open()
    await flushPromises()
    await run(wrapper).trigger('click')
    await flushPromises()
    vi.mocked(getBuild).mockResolvedValue({ ...queued, status, finishedAt: date })
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(startInstance).not.toHaveBeenCalled()
    expect(wrapper.get('[aria-label="运行应用流程"]').text()).toContain('未启动')
  },
)
it('keeps unknown build results for explicit confirmation, never blind retry or auto-start', async () => {
  vi.mocked(submitBuild).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果未知'))
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('确认上次构建请求')
  await finishBuild()
  expect(submitBuild).toHaveBeenCalledTimes(1)
  expect(startInstance).not.toHaveBeenCalled()
})
it('keeps unknown startup results for explicit confirmation without retrying', async () => {
  vi.mocked(startInstance).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '启动结果未知'))
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  await finishBuild()
  expect(wrapper.text()).toContain('确认上次启动请求')
  expect(wrapper.emitted('preview')).toBeUndefined()
  expect(run(wrapper).attributes('disabled')).toBeDefined()
  await vi.advanceTimersByTimeAsync(6000)
  expect(startInstance).toHaveBeenCalledTimes(1)
})
it('waits for a matching starting process without starting another one', async () => {
  vi.mocked(listInstances).mockResolvedValue([{ ...runtime, status: 'STARTING' }])
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  expect(wrapper.emitted('preview')).toBeUndefined()
  vi.mocked(listInstances).mockResolvedValue([runtime])
  await vi.advanceTimersByTimeAsync(2000)
  await flushPromises()
  expect(wrapper.emitted('preview')).toHaveLength(1)
  expect(startInstance).not.toHaveBeenCalled()
})
it('does not adopt a different build selected while automatic start is waiting', async () => {
  const { wrapper } = open()
  await flushPromises()
  await run(wrapper).trigger('click')
  await flushPromises()
  vi.mocked(listBuilds).mockResolvedValue({ items: [{ ...done, id: other }], nextCursor: null })
  vi.mocked(getBuild).mockResolvedValue({ ...done, id: other })
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新构建状态')!
    .trigger('click')
  await flushPromises()
  expect(startInstance).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('构建记录已变化')
})
it.each(['version', 'owner', 'project', 'unmount', 'cancel'] as const)(
  'revokes automatic start on %s',
  async (change) => {
    const { wrapper, session } = open()
    await flushPromises()
    await run(wrapper).trigger('click')
    await flushPromises()
    if (change === 'version') await wrapper.setProps({ currentVersionId: other })
    if (change === 'owner') session.identity = { userId: other, displayName: '访客', isGuest: true }
    if (change === 'project') await wrapper.setProps({ projectId: other })
    if (change === 'unmount') wrapper.unmount()
    if (change === 'cancel') await wrapper.get('[data-action="cancel-auto-run"]').trigger('click')
    await finishBuild()
    expect(startInstance).not.toHaveBeenCalled()
    expect(stopInstance).not.toHaveBeenCalled()
  },
)
