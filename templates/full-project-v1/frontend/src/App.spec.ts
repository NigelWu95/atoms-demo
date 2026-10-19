import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

const healthy = () => ({
  ok: true,
  json: async () => ({ status: 'UP', stage: 'INITIALIZED' }),
})
const mounted: VueWrapper[] = []
function render() {
  const wrapper = mount(App)
  mounted.push(wrapper)
  return wrapper
}
afterEach(() => {
  mounted.forEach((wrapper) => {
    if (wrapper.exists()) wrapper.unmount()
  })
  mounted.length = 0
  vi.useRealTimers()
})

describe('native application initialization', () => {
  it('shows loading then its own API state without claiming business completion', async () => {
    const request = vi.fn().mockResolvedValue(healthy())
    vi.stubGlobal('fetch', request)
    const wrapper = render()
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="status"]').text()).toContain('正在检查')
    await flushPromises()
    expect(request).toHaveBeenCalledExactlyOnceWith(
      './api/health',
      expect.objectContaining({
        method: 'GET',
        credentials: 'omit',
        redirect: 'error',
        signal: expect.any(AbortSignal),
      }),
    )
    expect(wrapper.get('[role="status"]').text()).toContain('前后端已连通')
    expect(wrapper.text()).toContain('尚未生成业务功能')
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  })

  it('allows retry after a failed request without displaying the raw server error', async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(new Error('PRIVATE_UPSTREAM_CANARY'))
      .mockResolvedValueOnce(healthy())
    vi.stubGlobal('fetch', request)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法连接')
    expect(wrapper.html()).not.toContain('PRIVATE_UPSTREAM_CANARY')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('前后端已连通')
  })

  it.each([
    { ok: false, json: async () => ({}) },
    { ok: true, json: async () => ({ status: 'UP' }) },
    {
      ok: true,
      json: async () => {
        throw new SyntaxError('PRIVATE_JSON_CANARY')
      },
    },
  ])('rejects a failed status or unexpected API body', async (response) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('前后端已连通')
    expect(wrapper.text()).not.toContain('PRIVATE_JSON_CANARY')
  })

  it('bounds a stalled request and permits retry after timeout', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: RequestInfo | URL, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            signal = options.signal ?? undefined
            signal?.addEventListener(
              'abort',
              () => reject(new DOMException('aborted', 'AbortError')),
              { once: true },
            )
          }),
      ),
    )
    const wrapper = render()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(signal?.aborted).toBe(true)
    expect(wrapper.get('[role="alert"]').text()).toContain('请求超时')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  })

  it('aborts in-flight work and clears the timeout on unmount', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: RequestInfo | URL, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            signal = options.signal ?? undefined
            signal?.addEventListener(
              'abort',
              () => reject(new DOMException('aborted', 'AbortError')),
              { once: true },
            )
          }),
      ),
    )
    const wrapper = render()
    wrapper.unmount()
    await Promise.resolve()
    expect(signal?.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })
})
