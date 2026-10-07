import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { createAppRouter } from './router'

const project = {
  id: 'project-1',
  name: '待办项目',
  ownerId: 'guest-1',
  projectRevision: 0,
  createdAt: '2026-10-07T00:00:00Z',
  updatedAt: '2026-10-07T00:00:00Z',
}
function mockApi() {
  return vi.fn().mockImplementation((path: string, options?: RequestInit) => {
    if (path === '/api/health') return Promise.resolve(Response.json({ status: 'UP' }))
    if (path === '/api/csrf')
      return Promise.resolve(Response.json({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }))
    if (path === '/api/session')
      return Promise.resolve(
        Response.json({ userId: 'guest-1', isGuest: true, displayName: '访客创作者' }),
      )
    if (path === '/api/projects' && options?.method === 'POST')
      return Promise.resolve(Response.json(project, { status: 201 }))
    if (path === '/api/projects/project-1') return Promise.resolve(Response.json(project))
    if (path === '/api/generation')
      return Promise.resolve(
        Response.json({
          configured: false,
          workerEnabled: true,
          remainingRequests: 0,
          model: 'deepseek-flash',
        }),
      )
    if (
      path.startsWith('/api/projects/project-1/runs?') ||
      path.startsWith('/api/projects/project-1/messages?')
    )
      return Promise.resolve(Response.json({ items: [], nextCursor: null }))
    if (path.startsWith('/api/projects?'))
      return Promise.resolve(Response.json({ items: [], nextCursor: null }))
    throw new Error(`Unexpected test endpoint: ${path}`)
  })
}

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
  it('fills examples, creates a project, and preserves its original draft on return', async () => {
    vi.stubGlobal('fetch', mockApi())
    const { wrapper, router } = await openApp()
    const example = wrapper.findAll('button').find((button) => button.text().includes('待办清单'))!
    await example.trigger('click')
    expect(router.currentRoute.value.path).toBe('/')
    expect(wrapper.get('textarea').element.value).toContain('待办')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects/project-1')
    expect(wrapper.text()).toContain('还没有生成应用')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('textarea').element.value).toContain('待办')
    await router.push('/')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toContain('待办')
    wrapper.unmount()
  })

  it('shows a real connection failure and retries successfully', async () => {
    const fetch = mockApi()
    const original = fetch.getMockImplementation()!
    let failed = false
    fetch.mockImplementation((path: string, options?: RequestInit) => {
      if (path === '/api/health' && !failed) {
        failed = true
        return Promise.reject(new Error('network'))
      }
      return original(path, options)
    })
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp()
    expect(wrapper.text()).toContain('服务连接失败')
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试连接'))!
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务已连接')
    wrapper.unmount()
  })

  it('blocks submission when session initialization fails and recovers on explicit retry', async () => {
    const fetch = mockApi()
    const original = fetch.getMockImplementation()!
    let fail = true
    fetch.mockImplementation((path: string, options?: RequestInit) => {
      if (path === '/api/session' && fail)
        return Promise.resolve(
          Response.json(
            {
              error: { code: 'CSRF_REJECTED', message: '安全验证失败', retryable: false },
              requestId: 'test-request',
            },
            { status: 403 },
          ),
        )
      return original(path, options)
    })
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp()
    await wrapper.get('textarea').setValue('一个项目')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('test-request')
    fail = false
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('重新连接会话'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps input and intent key after unknown create result, and ignores duplicate clicks', async () => {
    const fetch = mockApi()
    const original = fetch.getMockImplementation()!
    const keys: string[] = []
    fetch.mockImplementation((path: string, options?: RequestInit) => {
      if (path === '/api/projects' && options?.method === 'POST') {
        keys.push((options.headers as Record<string, string>)['Idempotency-Key']!)
        if (keys.length === 1) return Promise.reject(new TypeError('connection lost'))
      }
      return original(path, options)
    })
    vi.stubGlobal('fetch', fetch)
    const { wrapper, router } = await openApp()
    await wrapper.get('textarea').setValue('保留我的输入')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('保留我的输入')
    expect(router.currentRoute.value.path).toBe('/')
    void wrapper.get('form').trigger('submit')
    void wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    expect(router.currentRoute.value.path).toBe('/projects/project-1')
    wrapper.unmount()
  })
})
