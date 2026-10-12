import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import EngineeringBuild from './EngineeringBuild.vue'
import BuildArchiveDownload from './BuildArchiveDownload.vue'
import { useSessionStore } from '../stores/session'
import {
  getBuild,
  listBuilds,
  listBuildEvents,
  submitBuild,
  cancelBuild,
  type EngineeringBuild as Build,
} from '../api/builds'
import { ApiFailure } from '../api/client'
vi.mock('../api/builds', async (original) => ({
  ...(await original<typeof import('../api/builds')>()),
  getBuild: vi.fn(),
  listBuilds: vi.fn(),
  listBuildEvents: vi.fn(),
  submitBuild: vi.fn(),
  cancelBuild: vi.fn(),
}))
const version = {
  id: 'a0000000-0000-4000-8000-000000000001',
  projectId: 'b0000000-0000-4000-8000-000000000001',
  versionNo: 2,
  createdAt: '2026-10-13T00:00:00Z',
}
const queued: Build = {
  id: 'c0000000-0000-4000-8000-000000000001',
  projectId: version.projectId,
  versionId: version.id,
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
  createdAt: version.createdAt,
  startedAt: null,
  finishedAt: null,
  lastEventSeq: 1,
}
const wrappers: ReturnType<typeof mount>[] = []
function open() {
  const wrapper = mount(EngineeringBuild, {
    props: { projectId: version.projectId, version, loading: false },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.state = 'ready'
  session.identity = {
    userId: 'd0000000-0000-4000-8000-000000000001',
    isGuest: true,
    displayName: '访客',
  }
  vi.mocked(listBuilds).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(getBuild).mockResolvedValue(queued)
  vi.mocked(listBuildEvents).mockResolvedValue({ items: [], nextCursor: 0 })
  vi.mocked(submitBuild).mockResolvedValue({
    buildId: queued.id,
    projectId: version.projectId,
    versionId: version.id,
  })
  vi.mocked(cancelBuild).mockResolvedValue({ buildId: queued.id })
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
it('keeps empty/loading/session states explicit and never submits automatically', async () => {
  const wrapper = open()
  await flushPromises()
  expect(wrapper.text()).toContain('该版本尚无构建记录')
  expect(submitBuild).not.toHaveBeenCalled()
  await wrapper.setProps({ loading: true })
  expect(wrapper.get('[data-action="build"]').attributes('disabled')).toBeDefined()
  await wrapper.setProps({ loading: false, version: null })
  expect(wrapper.text()).toContain('尚无可构建的已保存版本')
  useSessionStore().state = 'failed'
  await flushPromises()
  expect(wrapper.text()).toContain('连接访客会话')
})
it('submits the selected version once and presents cancelling without claiming process exit', async () => {
  let finish!: (value: { buildId: string; projectId: string; versionId: string }) => void
  vi.mocked(submitBuild).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const wrapper = open()
  await flushPromises()
  const button = wrapper.get('[data-action="build"]')
  expect(button.text()).toContain('V2')
  await button.trigger('click')
  await button.trigger('click')
  expect(submitBuild).toHaveBeenCalledTimes(1)
  finish({ buildId: queued.id, projectId: version.projectId, versionId: version.id })
  await flushPromises()
  expect(wrapper.text()).toContain('等待构建')
  vi.mocked(getBuild).mockResolvedValue({
    ...queued,
    status: 'CANCELLING',
    startedAt: version.createdAt,
  })
  await wrapper.get('[data-action="cancel-build"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('正在取消')
  expect(wrapper.text()).not.toContain('已取消')
  expect(wrapper.emitted('changed')).toBeUndefined()
})
it('offers original-intent confirmation on response loss and preserves the version', async () => {
  vi.mocked(submitBuild).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果尚不确定'))
  const wrapper = open()
  await flushPromises()
  await wrapper.get('[data-action="build"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('结果尚未确认')
  await wrapper.get('[data-action="confirm-build"]').trigger('click')
  await flushPromises()
  expect(vi.mocked(submitBuild).mock.calls[0]![2]).toBe(vi.mocked(submitBuild).mock.calls[1]![2])
  expect(wrapper.props('version')).toEqual(version)
})
it('explicitly distinguishes a successful build from the old shared preview', async () => {
  const done: Build = {
    ...queued,
    status: 'SUCCEEDED',
    stage: 'COMPLETE',
    bundleId: 'b'.repeat(64),
    archiveSha256: 'c'.repeat(64),
    loadedRuntimeId: 'd'.repeat(64),
    jarSha256: 'e'.repeat(64),
    startedAt: version.createdAt,
    finishedAt: version.createdAt,
  }
  vi.mocked(listBuilds).mockResolvedValue({ items: [done], nextCursor: null })
  vi.mocked(getBuild).mockResolvedValue(done)
  const wrapper = open()
  await flushPromises()
  expect(wrapper.text()).toContain('构建完成，实例预览尚未接入')
  expect(wrapper.find('iframe').exists()).toBe(false)
  expect(wrapper.findComponent(BuildArchiveDownload).props('target')).toMatchObject({
    id: done.id,
    projectId: done.projectId,
  })
  expect(submitBuild).not.toHaveBeenCalled()
})
