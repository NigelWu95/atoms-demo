import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import InstancePreviewFrame from './InstancePreviewFrame.vue'
import { loadInstanceApp, type AppInstance } from '../api/instances'
import { mountFrame } from '../runtime/mountFrame'
vi.mock('../api/instances', () => ({
  loadInstanceApp: vi.fn(),
  instancePath: () => '/runtime',
  instanceState: vi.fn(),
}))
vi.mock('../runtime/mountFrame', () => ({ mountFrame: vi.fn() }))
const instance = { id: 'execution-a', projectId: 'project-a' } as AppInstance
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(loadInstanceApp).mockResolvedValue(instance)
  vi.mocked(mountFrame).mockReturnValue(vi.fn())
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
it('reports readiness only after identity validation and bridge handshake, and revokes on error', async () => {
  const wrapper = mount(InstancePreviewFrame, { props: { instance } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.emitted('availability')?.some((event) => event[1] === true) ?? false).toBe(false)
  const report = vi.mocked(mountFrame).mock.calls[0]![3]
  report('ready', '就绪')
  expect(wrapper.emitted('availability')?.at(-1)).toEqual([instance.id, true])
  report('saving', '保存中')
  report('saved', '已保存')
  expect(wrapper.emitted('availability')?.at(-1)).toEqual([instance.id, true])
  report('error', '连接中断')
  expect(wrapper.emitted('availability')?.at(-1)).toEqual([instance.id, false])
  wrapper.unmount()
  report('ready', '迟到响应')
  expect(wrapper.emitted('availability')?.at(-1)).toEqual([instance.id, false])
  wrappers.pop()
})
it('does not mount or enable a preview when runtime identity validation fails', async () => {
  vi.mocked(loadInstanceApp).mockRejectedValue(new Error('identity mismatch'))
  const wrapper = mount(InstancePreviewFrame, { props: { instance } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(mountFrame).not.toHaveBeenCalled()
  expect(wrapper.emitted('availability')?.at(-1)).toEqual([instance.id, false])
  expect(wrapper.find('[role="alert"]').exists()).toBe(true)
})
it('never enables a disposed bootstrap', async () => {
  let resolve!: (value: AppInstance) => void
  vi.mocked(loadInstanceApp).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const wrapper = mount(InstancePreviewFrame, { props: { instance } })
  wrapper.unmount()
  resolve(instance)
  await flushPromises()
  expect(mountFrame).not.toHaveBeenCalled()
  expect(wrapper.emitted('availability')?.some((event) => event[1] === true) ?? false).toBe(false)
})
