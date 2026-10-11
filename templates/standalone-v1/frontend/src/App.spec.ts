import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

vi.mock('./RuntimeFrame.vue', () => ({
  default: { props: ['title'], template: '<div data-testid="runtime">{{ title }}</div>' },
}))

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
const up = () =>
  new Response('{"status":"UP"}', { headers: { 'Content-Type': 'application/json' } })

describe('standalone startup shell', () => {
  it('shows only checking until real readiness completes', async () => {
    let complete!: (response: Response) => void
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(applicationResponse)
        .mockImplementationOnce(
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
    expect(wrapper.get('[role="status"]').text()).toContain('应用信息已就绪')
    expect(wrapper.get('h1').text()).toBe('我的计数器')
    expect(wrapper.text()).toContain('<b>安全显示的摘要</b>')
    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.get('[data-testid="runtime"]').text()).toBe('我的计数器')
    expect(wrapper.text()).not.toMatch(/应用运行成功|保存成功/)
    expect(wrapper.find('iframe').exists()).toBe(false)
    wrapper.unmount()
  })

  function applicationResponse(path: string) {
    const data =
      path === '/api/health'
        ? { status: 'UP' }
        : path === '/api/csrf'
          ? { token: 'a'.repeat(96), headerName: 'X-CSRF-TOKEN' }
          : path === '/api/session'
            ? { userId: '9972c85c-074e-48f8-a692-c2d4c20f2122', isGuest: true }
            : {
                title: '我的计数器',
                summary: '<b>安全显示的摘要</b>',
                runtime: 'web-static-v1',
                stateSchemaVersion: 1,
                loadedRuntimeId: 'a'.repeat(64),
              }
    return Promise.resolve(
      new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } }),
    )
  }

  it('keeps the last error visible during retry and prevents duplicate checks', async () => {
    let complete!: (response: Response) => void
    const fetcher = vi
      .fn()
      .mockImplementation(applicationResponse)
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
    expect(wrapper.get('[role="status"]').text()).toContain('应用信息已就绪')
    wrapper.unmount()
  })

  it('has semantic navigation, a single title and no platform management links', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(applicationResponse))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('a').attributes('href')).toBe('#main-content')
    expect(wrapper.get('main').attributes('id')).toBe('main-content')
    expect(wrapper.get('button').attributes('type')).toBe('button')
    expect(wrapper.text()).not.toMatch(/我的项目|工作台|API Key/)
    wrapper.unmount()
  })

  it('times out the whole initialization after fifteen seconds', async () => {
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
    await vi.advanceTimersByTimeAsync(15000)
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

  it('shows an expired session with a safe request ID and no automatic reconnect', async () => {
    const fetcher = vi.fn((path: string) =>
      path === '/api/app'
        ? Promise.resolve(
            new Response(
              JSON.stringify({
                error: { code: 'SESSION_REQUIRED', message: 'PRIVATE_ERROR', retryable: false },
                requestId: '9972c85c-074e-48f8-a692-c2d4c20f2122',
              }),
              { status: 401, headers: { 'Content-Type': 'application/json' } },
            ),
          )
        : applicationResponse(path),
    )
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('访客会话已失效')
    expect(wrapper.text()).not.toContain('PRIVATE_ERROR')
    expect(wrapper.text()).toContain('9972c85c-074e-48f8-a692-c2d4c20f2122')
    expect(fetcher).toHaveBeenCalledTimes(5)
    expect(wrapper.find('iframe').exists()).toBe(false)
    wrapper.unmount()
  })

  it('preserves the current frame when reload is cancelled and reconnects only on confirmation', async () => {
    const fetcher = vi.fn().mockImplementation(applicationResponse)
    vi.stubGlobal('fetch', fetcher)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('button').trigger('click')
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('未保存'))
    expect(fetcher).toHaveBeenCalledTimes(5)
    expect(wrapper.find('[data-testid="runtime"]').exists()).toBe(true)
    confirm.mockReturnValue(true)
    await wrapper.get('button').trigger('click')
    expect(wrapper.find('[data-testid="runtime"]').exists()).toBe(false)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(10)
    expect(wrapper.find('[data-testid="runtime"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
