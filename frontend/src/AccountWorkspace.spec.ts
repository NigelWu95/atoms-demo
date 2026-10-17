import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { api } from './api/client'
import type { Identity } from './api/session'
import { createAppRouter } from './router'

const guest: Identity = {
  userId: '11111111-1111-4111-8111-111111111111',
  isGuest: true,
  displayName: '访客创作者',
}
const registered = {
  userId: '22222222-2222-4222-8222-222222222222',
  isGuest: false as const,
  displayName: '邮箱创作者',
  email: 'creator@example.com',
  emailVerified: true,
  createdAt: '2026-10-17T04:05:06Z',
}

function mockApi(identity: Identity, profile: object = identity) {
  return vi.fn((path: string) => {
    if (path === '/api/health') return Promise.resolve(Response.json({ status: 'UP' }))
    if (path === '/api/csrf')
      return Promise.resolve(Response.json({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }))
    if (path === '/api/session' || path === '/api/me')
      return Promise.resolve(Response.json(identity))
    if (path === '/api/account') return Promise.resolve(Response.json(profile))
    if (path === '/api/account/password')
      return Promise.resolve(Response.json({ configured: false, updatedAt: null }))
    if (path === '/api/auth/methods')
      return Promise.resolve(Response.json({ emailCodeEnabled: true, passwordEnabled: true }))
    if (path.startsWith('/api/projects?'))
      return Promise.resolve(Response.json({ items: [], nextCursor: null }))
    if (path === '/api/generation')
      return Promise.resolve(
        Response.json({
          configured: false,
          workerEnabled: true,
          remainingRequests: 0,
          model: 'deepseek-flash',
        }),
      )
    throw new Error(`Unexpected test endpoint: ${path}`)
  })
}

async function openApp(path: string) {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, {
    global: { plugins: [createPinia(), router], stubs: { PublicFrame: true } },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  localStorage.clear()
  // jsdom has no layout/scroll implementation; actual scrolling is browser-tested.
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => {
  api.resetScope()
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('account and persistent workspace navigation', () => {
  it.each([
    ['returning guest', guest],
    ['signed-in account', registered],
  ] as const)(
    'keeps the landing page at / for a %s despite the old entry marker',
    async (_name, identity) => {
      localStorage.setItem('atoms.workspace.entered.v1', '1')
      vi.stubGlobal('fetch', mockApi(identity))
      const { wrapper, router } = await openApp('/')
      try {
        expect(router.currentRoute.value.path).toBe('/')
        expect(wrapper.find('.welcome-page').exists()).toBe(true)
        expect(wrapper.find('.sidebar').exists()).toBe(false)
        expect(wrapper.find('main textarea').exists()).toBe(true)
        expect(wrapper.get('header .account-link').text()).toBe(
          identity.isGuest ? '注册 / 登录' : '我的账号',
        )
      } finally {
        wrapper.unmount()
      }
    },
  )

  it('returns to the landing page on browser back after entering the workspace', async () => {
    vi.stubGlobal('fetch', mockApi(guest))
    const { wrapper, router } = await openApp('/')
    try {
      await wrapper.get('header a[href="/projects"]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.path).toBe('/projects')
      expect(wrapper.find('.sidebar').exists()).toBe(true)
      router.back()
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
      await flushPromises()
      expect(wrapper.find('.welcome-page').exists()).toBe(true)
      expect(wrapper.find('.sidebar').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps the same sidebar when starting another creation from the workbench', async () => {
    vi.stubGlobal('fetch', mockApi(guest))
    const { wrapper, router } = await openApp('/workbench')
    try {
      const sidebar = wrapper.get('.sidebar').element
      const createLink = wrapper
        .findAll('.sidebar nav a')
        .find((link) => link.text().includes('开始创作'))!
      await createLink.trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/create')
      expect(wrapper.get('.sidebar').element).toBe(sidebar)
      expect(wrapper.find('.entry-header').exists()).toBe(false)
      expect(wrapper.find('main textarea').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('opens workspace account management instead of the standalone login page for an account', async () => {
    vi.stubGlobal('fetch', mockApi(registered, registered))
    const { wrapper, router } = await openApp('/projects')
    try {
      const manage = wrapper.get('.sidebar .account-link')
      expect(manage.text()).toBe('管理账号')
      await manage.trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/settings/account')
      expect(wrapper.find('.sidebar').exists()).toBe(true)
      expect(wrapper.find('.entry-header').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('shows the current account email, identifier, and registration date from the private profile', async () => {
    // The session DTO intentionally has no email or registration date.
    const identity = {
      userId: registered.userId,
      isGuest: registered.isGuest,
      displayName: registered.displayName,
    }
    const fetch = mockApi(identity, registered)
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await openApp('/settings/account')
    try {
      const content = wrapper.get('main').text()
      expect(content).toContain(registered.email)
      expect(content).toContain(registered.userId)
      expect(content).toMatch(/注册时间[\s\S]*2026/)
      expect(wrapper.get('time').attributes('datetime')).toBe(registered.createdAt)
      expect(wrapper.get('time').text()).toBe(new Date(registered.createdAt).toLocaleString())
      expect(wrapper.get('.sidebar').text()).not.toContain(registered.email)
      expect(fetch).toHaveBeenCalledWith(
        '/api/account',
        expect.objectContaining({ method: 'GET', cache: 'no-store' }),
      )
    } finally {
      wrapper.unmount()
    }
  })
})
