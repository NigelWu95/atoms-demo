import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})
const up = () =>
  new Response('{"status":"UP"}', { headers: { 'Content-Type': 'application/json' } })

describe('standalone startup shell', () => {
  it('shows only checking until real readiness completes', async () => {
    let complete!: (response: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            complete = resolve
          }),
      ),
    )
    const wrapper = mount(App)
    expect(wrapper.get('[role="status"]').text()).toContain('正在连接服务')
    expect(wrapper.text()).not.toContain('服务连接正常')
    complete(up())
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('服务连接正常')
    expect(wrapper.text()).toContain('应用界面尚未接入')
    expect(wrapper.text()).not.toMatch(/应用运行成功|保存成功/)
    expect(wrapper.find('iframe').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the last error visible during retry and prevents duplicate checks', async () => {
    let complete!: (response: Response) => void
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('PRIVATE_NETWORK_TEXT'))
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            complete = resolve
          }),
      )
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法连接应用服务')
    expect(wrapper.text()).not.toContain('PRIVATE_NETWORK_TEXT')
    const button = wrapper.get('button')
    await button.trigger('click')
    await button.trigger('click')
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(button.attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toContain('无法连接应用服务')
    complete(up())
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('服务连接正常')
    wrapper.unmount()
  })

  it('has semantic navigation, a single title and no platform management links', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(up()))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('a').attributes('href')).toBe('#main-content')
    expect(wrapper.get('main').attributes('id')).toBe('main-content')
    expect(wrapper.get('button').attributes('type')).toBe('button')
    expect(wrapper.text()).not.toMatch(/我的项目|工作台|API Key/)
    wrapper.unmount()
  })

  it('times out after five seconds instead of remaining in loading forever', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            init.signal?.addEventListener('abort', () => reject(init.signal?.reason), {
              once: true,
            })
          }),
      ),
    )
    const wrapper = mount(App)
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.get('[role="alert"]').text()).toContain('连接超时')
    expect(wrapper.get('button').attributes('aria-disabled')).toBe('false')
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('aborts in-flight work and clears its timer on unmount', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined | null
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            signal = init.signal
            signal?.addEventListener('abort', () => reject(signal?.reason), { once: true })
          }),
      ),
    )
    const wrapper = mount(App)
    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})
