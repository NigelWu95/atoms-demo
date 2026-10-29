import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useSessionStore } from '../stores/session'
import { getLocalAddress } from '../api/projectDelivery'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'
import LocalApplicationLink from './LocalApplicationLink.vue'
import { ApiFailure } from '../api/client'

vi.mock('../api/projectDelivery', () => ({ getLocalAddress: vi.fn() }))
const run = {
  id: 'run',
  buildId: 'build',
  sourceVersionId: 'source',
  status: 'READY',
  stopRequested: false,
} as ExecutionTask
const address = {
  runId: run.id,
  buildId: run.buildId!,
  sourceVersionId: run.sourceVersionId,
  url: 'http://localhost:18203/',
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const wrapper = mount(LocalApplicationLink, {
    props: { projectId: 'project', ownerId: 'owner', run },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.state = 'ready'
  session.identity = { userId: 'owner' } as never
  vi.mocked(getLocalAddress).mockResolvedValue(address)
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.resetAllMocks()
  vi.useRealTimers()
})
it('retries only the preparing gateway and stops retrying after an owner change', async () => {
  vi.useFakeTimers()
  vi.mocked(getLocalAddress).mockRejectedValue(
    new ApiFailure('PUBLICATION_NOT_READY', '准备中', 409),
  )
  const wrapper = setup()
  await flushPromises()
  await vi.advanceTimersByTimeAsync(1000)
  expect(getLocalAddress).toHaveBeenCalledTimes(2)
  useSessionStore().identity = { userId: 'other' } as never
  await flushPromises()
  await vi.advanceTimersByTimeAsync(2000)
  expect(getLocalAddress).toHaveBeenCalledTimes(2)
  expect(wrapper.find('a').exists()).toBe(false)
})
it('opens the running application directly with no platform opener and copies the real address', async () => {
  const copy = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: copy }, configurable: true })
  const wrapper = setup()
  await flushPromises()
  expect(wrapper.get('a').attributes()).toMatchObject({
    href: address.url,
    target: '_blank',
    rel: 'noopener noreferrer',
  })
  expect(wrapper.get('input').element.value).toBe(address.url)
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(copy).toHaveBeenCalledWith(address.url)
  expect(wrapper.text()).toContain('已复制')
})
it('clears the direct link as soon as a stop is requested or the owner changes', async () => {
  const wrapper = setup()
  await flushPromises()
  await wrapper.setProps({ run: { ...run, status: 'STOPPING', stopRequested: true } })
  expect(wrapper.find('a').exists()).toBe(false)
  await wrapper.setProps({ run })
  await flushPromises()
  expect(wrapper.find('a').exists()).toBe(true)
  useSessionStore().identity = { userId: 'stranger' } as never
  await flushPromises()
  expect(wrapper.find('a').exists()).toBe(false)
  expect(getLocalAddress).toHaveBeenCalledTimes(2)
})
it('ignores a late address from the previous project', async () => {
  let finish!: (value: typeof address) => void
  vi.mocked(getLocalAddress).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const wrapper = setup()
  await wrapper.setProps({ projectId: 'other', run: null })
  finish(address)
  await flushPromises()
  expect(wrapper.find('a').exists()).toBe(false)
})
it('does not display an address from the wrong build and allows a fresh lookup', async () => {
  vi.mocked(getLocalAddress).mockResolvedValueOnce({ ...address, buildId: 'different-build' })
  const wrapper = setup()
  await flushPromises()
  expect(wrapper.find('a').exists()).toBe(false)
  expect(wrapper.get('[role=alert]').text()).toContain('响应格式')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('a').attributes('href')).toBe(address.url)
})
