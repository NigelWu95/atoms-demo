import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiFailure } from '../api/client'
import {
  getBuild,
  listBuilds,
  listBuildEvents,
  submitBuild,
  cancelBuild,
  type EngineeringBuild,
} from '../api/builds'
import { useEngineeringBuild } from './useEngineeringBuild'
vi.mock('../api/builds', async (original) => ({
  ...(await original<typeof import('../api/builds')>()),
  getBuild: vi.fn(),
  listBuilds: vi.fn(),
  listBuildEvents: vi.fn(),
  submitBuild: vi.fn(),
  cancelBuild: vi.fn(),
}))
const target = {
  ownerId: 'a0000000-0000-4000-8000-000000000001',
  projectId: 'b0000000-0000-4000-8000-000000000001',
  versionId: 'c0000000-0000-4000-8000-000000000001',
}
const queued: EngineeringBuild = {
  id: 'd0000000-0000-4000-8000-000000000001',
  projectId: target.projectId,
  versionId: target.versionId,
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
const done: EngineeringBuild = {
  ...queued,
  status: 'SUCCEEDED',
  stage: 'COMPLETE',
  startedAt: queued.createdAt,
  finishedAt: queued.createdAt,
  bundleId: 'b'.repeat(64),
  archiveSha256: 'c'.repeat(64),
  loadedRuntimeId: 'd'.repeat(64),
  jarSha256: 'e'.repeat(64),
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const selection = ref<typeof target | null>({ ...target })
  let state!: ReturnType<typeof useEngineeringBuild>
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useEngineeringBuild(() => selection.value)
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
  vi.mocked(listBuilds).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(getBuild).mockResolvedValue(queued)
  vi.mocked(listBuildEvents).mockResolvedValue({ items: [], nextCursor: 0 })
  vi.mocked(submitBuild).mockResolvedValue({
    buildId: queued.id,
    projectId: target.projectId,
    versionId: target.versionId,
  })
  vi.mocked(cancelBuild).mockResolvedValue({ buildId: queued.id })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})
it('restores a persisted build and stops polling after its server terminal state', async () => {
  vi.mocked(listBuilds).mockResolvedValue({ items: [queued], nextCursor: null })
  const { state } = setup()
  await flushPromises()
  expect(state.build.value?.id).toBe(queued.id)
  expect(submitBuild).not.toHaveBeenCalled()
  vi.mocked(getBuild).mockResolvedValue(done)
  await vi.advanceTimersByTimeAsync(2000)
  await flushPromises()
  expect(state.build.value?.status).toBe('SUCCEEDED')
  const calls = vi.mocked(getBuild).mock.calls.length
  await vi.advanceTimersByTimeAsync(10000)
  expect(getBuild).toHaveBeenCalledTimes(calls)
})
it('persists an unknown submit intent across remount and replays exactly its key and version', async () => {
  vi.mocked(submitBuild).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断网'))
  const first = setup()
  await flushPromises()
  await first.state.submit()
  expect(first.state.pending.value?.kind).toBe('submit')
  expect(first.state.canSubmit.value).toBe(false)
  const original = vi.mocked(submitBuild).mock.calls[0]!
  first.wrapper.unmount()
  const second = setup()
  await flushPromises()
  await second.state.confirm()
  expect(vi.mocked(submitBuild).mock.calls[1]!.slice(0, 3)).toEqual(original.slice(0, 3))
  expect(second.state.pending.value).toBeNull()
  expect(second.state.build.value?.id).toBe(queued.id)
})
it('ignores a late old-project result and aborts its request on target change', async () => {
  let finish!: (value: { items: EngineeringBuild[]; nextCursor: null }) => void
  vi.mocked(listBuilds).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const { state, selection } = setup()
  await flushPromises()
  const signal = vi.mocked(listBuilds).mock.calls[0]![1]
  selection.value = { ...target, projectId: 'e0000000-0000-4000-8000-000000000001' }
  await flushPromises()
  finish({ items: [queued], nextCursor: null })
  await flushPromises()
  expect(signal.aborted).toBe(true)
  expect(state.build.value).toBeNull()
})
it('retains a cancellation intent through response loss and never calls it cancelled locally', async () => {
  vi.mocked(listBuilds).mockResolvedValue({ items: [queued], nextCursor: null })
  vi.mocked(cancelBuild).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断网'))
  const { state } = setup()
  await flushPromises()
  await state.cancel()
  expect(state.build.value?.status).toBe('QUEUED')
  expect(state.pending.value?.kind).toBe('cancel')
  vi.mocked(getBuild).mockResolvedValue({
    ...queued,
    status: 'CANCELLING',
    startedAt: queued.createdAt,
  })
  await state.confirm()
  expect(vi.mocked(cancelBuild).mock.calls[0]![2]).toBe(vi.mocked(cancelBuild).mock.calls[1]![2])
  expect(state.build.value?.status).toBe('CANCELLING')
})

it('keeps the original accepted intent if its follow-up detail response is unavailable', async () => {
  vi.mocked(getBuild).mockRejectedValueOnce(
    new ApiFailure('BUILD_NOT_FOUND', '暂未读取到构建', 404),
  )
  const { state } = setup()
  await flushPromises()
  await state.submit()
  expect(state.pending.value?.kind).toBe('submit')
  await state.confirm()
  expect(vi.mocked(submitBuild).mock.calls[0]![2]).toBe(vi.mocked(submitBuild).mock.calls[1]![2])
  expect(state.pending.value).toBeNull()
})

it('does not send a new intent when browser storage cannot preserve its key', async () => {
  const { state } = setup()
  await flushPromises()
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('storage unavailable')
  })
  try {
    await state.submit()
    expect(submitBuild).not.toHaveBeenCalled()
    expect(state.error.value).toContain('浏览器存储')
  } finally {
    storage.mockRestore()
  }
})
