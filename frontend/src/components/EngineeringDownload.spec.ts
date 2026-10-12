import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import EngineeringDownload from './EngineeringDownload.vue'
import { downloadEngineering } from '../api/engineering'
import { ApiFailure } from '../api/client'
import { useSessionStore } from '../stores/session'

vi.mock('../api/engineering', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/engineering')>()),
  downloadEngineering: vi.fn(),
}))
const version = {
  id: 'a0fa4b17-67ef-43dc-bfb7-ad630498a614',
  projectId: 'fe04a8f6-1d98-496f-aa83-2764b3f748c1',
  versionNo: 2,
  createdAt: '',
}
const createUrl = vi.fn(() => 'blob:test-export')
const revokeUrl = vi.fn()
let click: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  setActivePinia(createPinia())
  useSessionStore().state = 'ready'
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = createUrl
      static revokeObjectURL = revokeUrl
    },
  )
  click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  vi.mocked(downloadEngineering).mockResolvedValue(new Blob(['zip']))
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})
function open() {
  return mount(EngineeringDownload, {
    props: { projectId: version.projectId, version, loading: false },
  })
}

it('explains empty, loading and disconnected states without issuing requests', async () => {
  const wrapper = open()
  try {
    await wrapper.setProps({ version: null })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('尚无可下载的已保存版本')
    await wrapper.setProps({ loading: true })
    expect(wrapper.text()).toContain('正在加载版本')
    await wrapper.setProps({ version, loading: false })
    useSessionStore().state = 'failed'
    await flushPromises()
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('连接访客会话')
    expect(downloadEngineering).not.toHaveBeenCalled()
  } finally {
    wrapper.unmount()
  }
})
it('downloads the selected version once and cleans up link, timer and object URL', async () => {
  let finish!: (blob: Blob) => void
  vi.mocked(downloadEngineering).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const wrapper = open()
  try {
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await wrapper.get('button').trigger('click')
    expect(downloadEngineering).toHaveBeenCalledTimes(1)
    expect(downloadEngineering).toHaveBeenCalledWith(version, expect.any(AbortSignal))
    finish(new Blob(['zip']))
    await flushPromises()
    expect(click).toHaveBeenCalledOnce()
    expect(document.querySelector('a[download]')).toBeNull()
    expect(wrapper.get('[role="status"]').text()).toContain('V2 工程已交给浏览器下载')
    expect(revokeUrl).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(30000)
    expect(revokeUrl).toHaveBeenCalledWith('blob:test-export')
  } finally {
    wrapper.unmount()
  }
  expect(vi.getTimerCount()).toBe(0)
})
it('shows a request ID and allows manual retry without changing the selected version', async () => {
  vi.mocked(downloadEngineering).mockRejectedValueOnce(
    new ApiFailure('EXPORT_BUSY', '稍后重试', 503, 'req-1'),
  )
  const wrapper = open()
  try {
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('req-1')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    expect(createUrl).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(click).toHaveBeenCalledOnce()
  } finally {
    wrapper.unmount()
  }
  expect(revokeUrl).toHaveBeenCalledOnce()
})
it.each(['version', 'project', 'unmount'] as const)(
  'aborts and discards stale results on %s change',
  async (mode) => {
    let finish!: (blob: Blob) => void
    vi.mocked(downloadEngineering).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const wrapper = open()
    await wrapper.get('button').trigger('click')
    const signal = vi.mocked(downloadEngineering).mock.calls[0]![1]
    if (mode === 'unmount') wrapper.unmount()
    else if (mode === 'project') await wrapper.setProps({ projectId: 'other' })
    else
      await wrapper.setProps({
        version: { ...version, id: 'b0fa4b17-67ef-43dc-bfb7-ad630498a614', versionNo: 1 },
      })
    expect(signal.aborted).toBe(true)
    finish(new Blob(['stale']))
    await flushPromises()
    expect(click).not.toHaveBeenCalled()
    expect(createUrl).not.toHaveBeenCalled()
    if (mode !== 'unmount') wrapper.unmount()
  },
)
