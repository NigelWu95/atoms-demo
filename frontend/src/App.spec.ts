import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { createAppRouter } from './router'
import { useSessionStore } from './stores/session'

const project = {
  id: 'project-1',
  name: '待办项目',
  ownerId: 'guest-1',
  projectRevision: 0,
  createdAt: '2026-10-07T00:00:00Z',
  updatedAt: '2026-10-07T00:00:00Z',
}
function mockApi() {
  let savedDraft = { prompt: '', revision: 0 }
  return vi.fn().mockImplementation((path: string, options?: RequestInit) => {
    if (path === '/api/health') return Promise.resolve(Response.json({ status: 'UP' }))
    if (path === '/api/development-mode') return Promise.resolve(Response.json({ mode: 'LEGACY' }))
    if (path === '/api/auth/methods')
      return Promise.resolve(Response.json({ emailCodeEnabled: true }))
    if (path === '/api/csrf')
      return Promise.resolve(Response.json({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }))
    if (path === '/api/session')
      return Promise.resolve(
        Response.json({ userId: 'guest-1', isGuest: true, displayName: '访客创作者' }),
      )
    if (path === '/api/projects' && options?.method === 'POST')
      return Promise.resolve(Response.json(project, { status: 201 }))
    if (path === '/api/projects/project-1') return Promise.resolve(Response.json(project))
    if (path === '/api/projects/project-1/draft') {
      if (options?.method === 'PUT') {
        const input = JSON.parse(String(options.body)) as { prompt: string }
        savedDraft = { prompt: input.prompt, revision: savedDraft.revision + 1 }
      }
      return Promise.resolve(Response.json(savedDraft))
    }
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

beforeEach(() => localStorage.clear())
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

async function openApp(path = '/') {
  // jsdom has no layout/scroll implementation; actual scrolling is browser-tested.
  vi.stubGlobal('scrollTo', vi.fn())
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, {
    global: { plugins: [createPinia(), router], stubs: { PublicFrame: true } },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('foundation website', () => {
  it.each(['/', '/account', '/account?returnTo=/projects'])(
    'uses a full-width entry layout with account access on %s',
    async (path) => {
      vi.stubGlobal('fetch', mockApi())
      const { wrapper } = await openApp(path)
      try {
        expect(wrapper.find('.sidebar').exists()).toBe(false)
        expect(wrapper.get('header .brand').attributes('href')).toBe('/')
        expect(wrapper.get('header .account-link').text()).toBe('注册 / 登录')
        expect(wrapper.get('header .account-link').attributes('href')).toBe(
          path === '/' ? '/account?returnTo=/' : path,
        )
        expect(wrapper.get('header a[href="/projects"]').text()).toBe('我的项目')
        const session = useSessionStore()
        session.identity = { userId: 'account-1', isGuest: false, displayName: '已注册用户' }
        await flushPromises()
        expect(wrapper.get('header .account-link').text()).toBe('我的账号')
        expect(wrapper.find('.sidebar').exists()).toBe(false)
        expect(wrapper.get('.account-link').attributes('href')).toBe('/settings/account')
      } finally {
        wrapper.unmount()
      }
    },
  )
  it('retains the workspace navigation outside the entry pages', async () => {
    vi.stubGlobal('fetch', mockApi())
    const { wrapper } = await openApp('/projects')
    expect(wrapper.find('.sidebar').exists()).toBe(true)
    expect(wrapper.find('.entry-header').exists()).toBe(false)
    wrapper.unmount()
  })
  it('only shows the inaccessible-project return notice for a signed-in account', async () => {
    vi.stubGlobal('fetch', mockApi())
    const { wrapper } = await openApp('/projects?notice=account-project-unavailable')
    expect(wrapper.text()).not.toContain('原项目不存在或不属于当前账号')
    const session = useSessionStore()
    session.identity = { userId: 'account', isGuest: false, displayName: '账号' }
    await flushPromises()
    expect(wrapper.get('.simple-page [role="status"]').text()).toContain(
      '原项目不存在或不属于当前账号',
    )
    wrapper.unmount()
  })
  it('hides but preserves a private view during identity revalidation', async () => {
    vi.stubGlobal('fetch', mockApi())
    const { wrapper } = await openApp()
    try {
      const input = wrapper.get('textarea').element
      await wrapper.get('textarea').setValue('仍属于同一身份的草稿')
      const session = useSessionStore()
      session.state = 'connecting'
      await flushPromises()
      expect(wrapper.get('textarea').element).toBe(input)
      session.state = 'ready'
      await flushPromises()
      expect(wrapper.get('textarea').element).toBe(input)
      expect(input.value).toBe('仍属于同一身份的草稿')
    } finally {
      wrapper.unmount()
    }
  })
  it('keeps welcome focused on idea creation without fetching project history', async () => {
    const fetch = mockApi()
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp()
    try {
      expect(wrapper.find('.welcome-page').exists()).toBe(true)
      expect(wrapper.find('.project-row').exists()).toBe(false)
      expect(fetch.mock.calls.some(([url]) => String(url).startsWith('/api/projects?'))).toBe(false)
      expect(wrapper.get('textarea').attributes('aria-describedby')).toBe('prompt-hint')
      expect(wrapper.text()).toContain('在工作台提交需求开始开发')
    } finally {
      wrapper.unmount()
    }
  })
  it('shows saved generation state on the projects page', async () => {
    const fetch = mockApi()
    const original = fetch.getMockImplementation()!
    fetch.mockImplementation((url: string, options?: RequestInit) =>
      url.startsWith('/api/projects?')
        ? Promise.resolve(
            Response.json({
              items: [
                { ...project, name: '已生成项目', currentVersionId: 'version-1' },
                { ...project, id: 'project-2', name: '空白项目', currentVersionId: null },
                { ...project, id: 'project-3', name: '完整工程项目', currentVersionId: null },
              ],
              nextCursor: null,
              sourceVersions: { 'project-3': 'source-version-1' },
            }),
          )
        : original(url, options),
    )
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp('/projects')
    try {
      const rows = wrapper.findAll('.project-row')
      expect(rows[0]!.text()).toContain('已生成')
      expect(rows[0]!.text()).not.toContain('尚未生成')
      expect(rows[1]!.text()).toContain('尚未生成')
      expect(rows[2]!.text()).toContain('工程已保存')
      expect(rows[2]!.text()).not.toContain('尚未生成')
      expect(wrapper.text()).not.toContain('AI 生成稍后接入')
    } finally {
      wrapper.unmount()
    }
  })
  it('does not mount the creator sidebar while the initial public route is resolving', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        Response.json({ error: { code: 'APP_NOT_PUBLISHED', message: '已下线' } }, { status: 404 }),
      )
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('scrollTo', vi.fn())
    const history = createMemoryHistory()
    history.replace(`/a/${'c'.repeat(32)}`)
    const router = createAppRouter(history)
    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
    await router.isReady()
    await flushPromises()
    expect(fetch.mock.calls.map((call) => call[0])).toEqual([`/api/public/apps/${'c'.repeat(32)}`])
    wrapper.unmount()
  })
  it('public app pages omit creator navigation and never request private resources', async () => {
    const slug = 'a'.repeat(32)
    const fetch = mockApi()
    const original = fetch.getMockImplementation()!
    fetch.mockImplementation((path: string, options?: RequestInit) =>
      path === `/api/public/apps/${slug}`
        ? Promise.resolve(
            Response.json({
              slug,
              versionId: '11111111-1111-4111-8111-111111111111',
              versionNo: 1,
              title: '公开应用',
              summary: '公开说明',
              stateSchemaVersion: 1,
              initialState: {},
            }),
          )
        : original(path, options),
    )
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp(`/a/${slug}`)
    expect(wrapper.get('h1').text()).toBe('公开应用')
    expect(wrapper.find('.sidebar').exists()).toBe(false)
    expect(wrapper.find('public-frame-stub').exists()).toBe(true)
    expect(
      fetch.mock.calls
        .map((call) => call[0])
        .some((path) => /projects|generation|health/.test(path)),
    ).toBe(false)
    wrapper.unmount()
  })
  it('unpublished apps show an unavailable page without creating a visitor or frame', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { error: { code: 'APP_NOT_PUBLISHED', message: '应用未发布或已下线' } },
          { status: 404 },
        ),
      )
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp(`/a/${'b'.repeat(32)}`)
    expect(wrapper.get('[role="alert"]').text()).toContain('应用未发布或已下线')
    expect(wrapper.find('public-frame-stub').exists()).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
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
    await vi.waitFor(() => expect(wrapper.text()).toContain('草稿已保存'))
    await router.push('/')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(wrapper.find('.sidebar').exists()).toBe(false)
    expect(wrapper.find('.welcome-page').exists()).toBe(true)
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
