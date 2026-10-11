import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RuntimeFrame from './RuntimeFrame.vue'
import { mountFrame } from './runtime/mountFrame'
import type { BridgeReport } from './runtime/bridge'
import { createStateHandler } from './api/state'

vi.mock('./runtime/mountFrame', () => ({ mountFrame: vi.fn() }))
vi.mock('./api/state', () => ({ createStateHandler: vi.fn(() => vi.fn()) }))
afterEach(() => vi.resetAllMocks())

describe('standalone runtime component', () => {
  it('connects the current runtime to real state transport and aborts on disposal', async () => {
    const dispose = vi.fn()
    vi.mocked(mountFrame).mockReturnValue(dispose)
    const wrapper = mount(RuntimeFrame, {
      props: { title: '计数器', loadedRuntimeId: 'a'.repeat(64) },
    })
    const [, handle, report, title] = vi.mocked(mountFrame).mock.calls[0]!
    expect(title).toBe('计数器')
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    expect(createStateHandler).toHaveBeenCalledWith('a'.repeat(64), expect.any(AbortSignal))
    expect(handle).toBe(vi.mocked(createStateHandler).mock.results[0]!.value)
    report('ready', '应用已启动')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('应用已启动')
    expect(wrapper.text()).toContain('当前访客')
    report('unknown', '保存结果尚未确认，请重试原操作。')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('尚未确认')
    expect(wrapper.text()).toContain('不会撤销')
    wrapper.unmount()
    expect(dispose).toHaveBeenCalledOnce()
    expect(vi.mocked(createStateHandler).mock.calls[0]![1].aborted).toBe(true)
  })

  it('renders runtime errors as text and keeps retry guidance outside the sandbox', async () => {
    let report!: BridgeReport
    vi.mocked(mountFrame).mockImplementation((_container, _handle, notify) => {
      report = notify
      return vi.fn()
    })
    const wrapper = mount(RuntimeFrame, {
      props: { title: '应用', loadedRuntimeId: 'a'.repeat(64) },
    })
    report('error', '<img src=x onerror=alert(1)>')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('重新加载应用')
    wrapper.unmount()
  })
})
