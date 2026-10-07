import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { createAppRouter } from './router'

afterEach(() => vi.unstubAllGlobals())

async function openApp() {
  // jsdom has no layout/scroll implementation; actual scrolling is browser-tested.
  vi.stubGlobal('scrollTo', vi.fn())
  const router = createAppRouter(createMemoryHistory())
  await router.push('/')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('foundation website', () => {
  it('fills examples, opens a draft workbench, and preserves the draft on return', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    const { wrapper, router } = await openApp()
    const example = wrapper.findAll('button').find((button) => button.text().includes('待办清单'))!
    await example.trigger('click')
    expect(router.currentRoute.value.path).toBe('/')
    expect(wrapper.get('textarea').element.value).toContain('待办')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/workbench')
    expect(wrapper.text()).toContain('还没有生成应用')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await router.push('/')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toContain('待办')
    wrapper.unmount()
  })

  it('shows a real connection failure and retries successfully', async () => {
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(new Response('{"status":"UP"}'))
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp()
    expect(wrapper.text()).toContain('服务连接失败')
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试连接'))!
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务已连接')
    wrapper.unmount()
  })
})
