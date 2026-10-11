import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RuntimeFrame from './RuntimeFrame.vue'
import { mountFrame } from './runtime/mountFrame'
import type { BridgeReport } from './runtime/bridge'

vi.mock('./runtime/mountFrame', () => ({ mountFrame: vi.fn() }))
afterEach(() => vi.resetAllMocks())

describe('standalone runtime component', () => {
  it('mounts only the app title, refuses unimplemented storage and disposes once', async () => {
    const dispose = vi.fn()
    vi.mocked(mountFrame).mockReturnValue(dispose)
    const wrapper = mount(RuntimeFrame, { props: { title: '计数器' } })
    const [, handle, report, title] = vi.mocked(mountFrame).mock.calls[0]!
    expect(title).toBe('计数器')
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    await expect(handle({ type: 'STATE_GET', payload: {} }, 'key')).rejects.toMatchObject({
      code: 'READ_ONLY_PREVIEW',
    })
    await expect(
      handle({ type: 'STATE_SAVE', payload: { value: {}, expectedRevision: 0 } }, 'key'),
    ).rejects.toMatchObject({ code: 'READ_ONLY_PREVIEW' })
    report('ready', '应用已启动')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('应用已启动')
    expect(wrapper.text()).toContain('尚未接入')
    wrapper.unmount()
    expect(dispose).toHaveBeenCalledOnce()
  })

  it('renders runtime errors as text and keeps retry guidance outside the sandbox', async () => {
    let report!: BridgeReport
    vi.mocked(mountFrame).mockImplementation((_container, _handle, notify) => {
      report = notify
      return vi.fn()
    })
    const wrapper = mount(RuntimeFrame, { props: { title: '应用' } })
    report('error', '<img src=x onerror=alert(1)>')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('重新加载应用')
    wrapper.unmount()
  })
})
