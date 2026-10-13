import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { listInstances, startInstance, type AppInstance } from '../api/instances'
import { useSessionStore } from '../stores/session'
import InstancePanel from './InstancePanel.vue'
import type { EngineeringBuild } from '../api/builds'
vi.mock('../api/instances', async (original) => ({
  ...(await original<typeof import('../api/instances')>()),
  listInstances: vi.fn(),
  startInstance: vi.fn(),
  stopInstance: vi.fn(),
}))
const id = 'a0000000-0000-4000-8000-000000000001'
const instance: AppInstance = {
  id,
  projectId: id,
  versionId: id,
  buildId: id,
  status: 'RUNNING',
  loadedRuntimeId: 'a'.repeat(64),
  errorCode: null,
  createdAt: '2026-10-13T00:00:00Z',
  startedAt: '2026-10-13T00:00:00Z',
  finishedAt: null,
}
const build = { id, projectId: id, versionId: id, status: 'SUCCEEDED' } as EngineeringBuild
const wrappers: ReturnType<typeof mount>[] = []
function open() {
  const pinia = createPinia(),
    session = useSessionStore(pinia)
  session.state = 'ready'
  session.identity = { userId: id, displayName: '访客', isGuest: true }
  const wrapper = mount(InstancePanel, {
    props: { projectId: id, versionId: id, currentVersionId: id, build },
    global: { plugins: [pinia], stubs: { BuildArchiveDownload: true } },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  sessionStorage.clear()
  vi.mocked(listInstances).mockResolvedValue([])
  vi.mocked(startInstance).mockResolvedValue({ instanceId: id })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.useRealTimers()
})
it('requires explicit start, disables historical start and never starts from observation', async () => {
  const wrapper = open()
  await flushPromises()
  expect(startInstance).not.toHaveBeenCalled()
  expect(wrapper.get('[data-action="start-instance"]').attributes('disabled')).toBeUndefined()
  await wrapper.setProps({ currentVersionId: 'b0000000-0000-4000-8000-000000000001' })
  expect(wrapper.get('[data-action="start-instance"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('历史版本只读')
})
it('only exposes the matching running version and clears preview when stopped', async () => {
  vi.mocked(listInstances).mockResolvedValue([instance])
  const wrapper = open()
  await flushPromises()
  expect(wrapper.emitted('running')?.at(-1)).toEqual([instance])
  vi.mocked(listInstances).mockResolvedValue([
    { ...instance, status: 'STOPPED', finishedAt: instance.createdAt },
  ])
  await vi.advanceTimersByTimeAsync(2000)
  await flushPromises()
  expect(wrapper.emitted('running')?.at(-1)).toEqual([null])
  expect(wrapper.text()).toContain('已停止')
})
