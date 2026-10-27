import { defineComponent, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { useEngineeringPreview } from './useEngineeringPreview'
import { openPreview, revokePreview, type DeliveryPreview } from '../api/projectDelivery'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'
vi.mock('../api/projectDelivery', () => ({
  openPreview: vi.fn(),
  revokePreview: vi.fn().mockResolvedValue(undefined),
}))
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
})
afterEach(() => vi.useRealTimers())
const project = '00000000-0000-4000-8000-000000000001',
  run = '00000000-0000-4000-8000-000000000002'
function setup() {
  const target = ref<{ project: string; owner: string } | null>({ project, owner: 'owner' }),
    runs = ref<ExecutionTask[]>([{ id: run, status: 'READY' } as ExecutionTask])
  let preview!: ReturnType<typeof useEngineeringPreview>
  const wrapper = mount(
    defineComponent({
      setup() {
        preview = useEngineeringPreview(
          () => target.value,
          () => runs.value,
        )
        return () => null
      },
    }),
  )
  return { target, runs, preview, wrapper }
}
function grant(): DeliveryPreview {
  return {
    previewId: run,
    runId: run,
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    iframeUrl: `/api/delivery/preview/${'A'.repeat(43)}/`,
    identity: {
      projectId: project,
      buildId: run,
      sourceVersionId: run,
      treeSha256: 'a'.repeat(64),
    },
  }
}
it('status polling keeps the same iframe grant and stop revokes it', async () => {
  vi.mocked(openPreview).mockResolvedValue(grant())
  const s = setup()
  await s.preview.open(run)
  const original = s.preview.preview.value
  s.runs.value = [{ id: run, status: 'READY' } as ExecutionTask]
  await flushPromises()
  expect(s.preview.preview.value).toEqual(original)
  expect(openPreview).toHaveBeenCalledTimes(1)
  s.runs.value = [{ id: run, status: 'STOPPED' } as ExecutionTask]
  await flushPromises()
  expect(s.preview.preview.value).toBeNull()
  expect(revokePreview).toHaveBeenCalledWith(project, run)
  s.wrapper.unmount()
})
it('expires the grant without silently creating another one', async () => {
  vi.mocked(openPreview).mockResolvedValue(grant())
  const s = setup()
  await s.preview.open(run)
  await vi.advanceTimersByTimeAsync(600_001)
  expect(s.preview.preview.value).toBeNull()
  expect(s.preview.error.value).toContain('过期')
  expect(openPreview).toHaveBeenCalledTimes(1)
  s.wrapper.unmount()
})
it('late issuance after account switch cannot restore the private preview', async () => {
  let finish!: (v: DeliveryPreview) => void
  vi.mocked(openPreview).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const s = setup(),
    opening = s.preview.open(run)
  s.target.value = null
  finish(grant())
  await opening
  expect(s.preview.preview.value).toBeNull()
  s.wrapper.unmount()
})
