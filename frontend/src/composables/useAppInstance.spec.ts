import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiFailure } from '../api/client'
import { listInstances, startInstance, stopInstance, type AppInstance } from '../api/instances'
import { useAppInstance } from './useAppInstance'
vi.mock('../api/instances', async (original) => ({
  ...(await original<typeof import('../api/instances')>()),
  listInstances: vi.fn(),
  startInstance: vi.fn(),
  stopInstance: vi.fn(),
}))
const projectId = 'a0000000-0000-4000-8000-000000000001'
const buildId = 'b0000000-0000-4000-8000-000000000001'
const running: AppInstance = {
  id: buildId,
  projectId,
  buildId,
  versionId: buildId,
  status: 'RUNNING',
  loadedRuntimeId: 'a'.repeat(64),
  errorCode: null,
  createdAt: '2026-10-13T00:00:00Z',
  startedAt: '2026-10-13T00:00:00Z',
  finishedAt: null,
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const selection = ref<{ ownerId: string; projectId: string } | null>({
    ownerId: projectId,
    projectId,
  })
  let state!: ReturnType<typeof useAppInstance>
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useAppInstance(() => selection.value)
        return () => null
      },
    }),
  )
  wrappers.push(wrapper)
  return { state, selection, wrapper }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  sessionStorage.clear()
  vi.mocked(listInstances).mockResolvedValue([])
  vi.mocked(startInstance).mockResolvedValue({ instanceId: buildId })
  vi.mocked(stopInstance).mockResolvedValue({ instanceId: buildId })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.useRealTimers()
})
it('only observes on mount and polls until stopped', async () => {
  vi.mocked(listInstances).mockResolvedValue([running])
  const { state } = setup()
  await flushPromises()
  expect(startInstance).not.toHaveBeenCalled()
  expect(state.instance.value?.status).toBe('RUNNING')
  await state.stop()
  expect(stopInstance).toHaveBeenCalledOnce()
  vi.mocked(listInstances).mockResolvedValue([
    { ...running, status: 'STOPPED', finishedAt: running.createdAt },
  ])
  await vi.advanceTimersByTimeAsync(2000)
  await flushPromises()
  expect(state.instance.value?.status).toBe('STOPPED')
})
it('preserves the exact unknown start intent across remount without automatic replay', async () => {
  vi.mocked(startInstance).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断网'))
  const first = setup()
  await flushPromises()
  await first.state.start(buildId)
  const original = vi.mocked(startInstance).mock.calls[0]!
  first.wrapper.unmount()
  const second = setup()
  await flushPromises()
  expect(startInstance).toHaveBeenCalledOnce()
  expect(second.state.pending.value).not.toBeNull()
  await second.state.confirm()
  expect(vi.mocked(startInstance).mock.calls[1]!.slice(0, 3)).toEqual(original.slice(0, 3))
  expect(second.state.pending.value).toBeNull()
})
it('aborts old project reads and ignores their late responses', async () => {
  let finish!: (items: AppInstance[]) => void
  vi.mocked(listInstances).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const { state, selection } = setup()
  await flushPromises()
  const signal = vi.mocked(listInstances).mock.calls[0]![1]
  selection.value = null
  await flushPromises()
  finish([running])
  await flushPromises()
  expect(signal.aborted).toBe(true)
  expect(state.instance.value).toBeNull()
})
