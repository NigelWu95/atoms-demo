import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import BuildArchiveDownload from './BuildArchiveDownload.vue'
import { downloadBuildArchive } from '../api/buildArchive'
import { useSessionStore } from '../stores/session'
vi.mock('../api/buildArchive', async (original) => ({
  ...(await original<typeof import('../api/buildArchive')>()),
  downloadBuildArchive: vi.fn(),
}))
const target = {
  id: 'a0000000-0000-4000-8000-000000000001',
  projectId: 'b0000000-0000-4000-8000-000000000001',
}
const createUrl = vi.fn(() => 'blob:build'),
  revokeUrl = vi.fn()
let click: ReturnType<typeof vi.spyOn>
beforeEach(() => {
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
  click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  vi.mocked(downloadBuildArchive).mockResolvedValue(new Blob(['zip']))
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
it('offers the exact snapshot and releases object URLs on unmount', async () => {
  const wrapper = mount(BuildArchiveDownload, { props: { target } })
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(downloadBuildArchive).toHaveBeenCalledWith(target, expect.any(AbortSignal))
  expect(click).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('已交给浏览器下载')
  wrapper.unmount()
  expect(revokeUrl).toHaveBeenCalledWith('blob:build')
})
it('keeps a pending download when polling replaces the target with the same identity', async () => {
  let resolve!: (blob: Blob) => void
  vi.mocked(downloadBuildArchive).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const wrapper = mount(BuildArchiveDownload, { props: { target } })
  try {
    await wrapper.get('button').trigger('click')
    const signal = vi.mocked(downloadBuildArchive).mock.calls[0]![1]
    await wrapper.setProps({ target: { ...target } })
    expect(signal.aborted).toBe(false)
    resolve(new Blob(['zip']))
    await flushPromises()
    expect(click).toHaveBeenCalledOnce()
  } finally {
    wrapper.unmount()
  }
})
it.each(['change', 'cancel', 'session'] as const)(
  'aborts and ignores late results on %s',
  async (mode) => {
    let resolve!: (blob: Blob) => void
    vi.mocked(downloadBuildArchive).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = mount(BuildArchiveDownload, { props: { target } })
    await wrapper.get('button').trigger('click')
    const signal = vi.mocked(downloadBuildArchive).mock.calls[0]![1]
    if (mode === 'change')
      await wrapper.setProps({ target: { ...target, id: 'c0000000-0000-4000-8000-000000000001' } })
    else if (mode === 'cancel')
      await wrapper.get('[data-action="cancel-download"]').trigger('click')
    else {
      useSessionStore().state = 'failed'
      await flushPromises()
    }
    expect(signal.aborted).toBe(true)
    resolve(new Blob(['late']))
    await flushPromises()
    expect(createUrl).not.toHaveBeenCalled()
    wrapper.unmount()
  },
)
