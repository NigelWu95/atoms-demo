import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import AccountPage from './AccountPage.vue'
import { useSessionStore } from '../stores/session'
import { api, ApiFailure } from '../api/client'
import { useDraftStore } from '../stores/draft'
import * as projects from '../api/projects'

afterEach(() => {
  api.resetScope()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
async function open(returnTo?: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.identity = { userId: 'guest', isGuest: true, displayName: '访客' }
  session.state = 'ready'
  api.setCsrf({ token: 'test', headerName: 'X-CSRF-TOKEN' })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push({ path: '/account', query: returnTo ? { returnTo } : {} })
  const wrapper = mount(AccountPage, { global: { plugins: [pinia, router] } })
  return { wrapper, session, router }
}
it('presents a standalone account card with return navigation and correct reconnect guidance', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ emailCodeEnabled: true })))
  const { wrapper, session } = await open()
  await flushPromises()
  expect(wrapper.get('.account-back').attributes('href')).toBe('/')
  expect(wrapper.find('.account-details').exists()).toBe(true)
  expect(wrapper.get('#account-warning').text()).toContain('不会合并当前访客的项目')
  session.state = 'failed'
  await flushPromises()
  expect(wrapper.text()).toContain('页首重新连接')
  expect(wrapper.text()).not.toContain('侧栏')
  wrapper.unmount()
})
it('does not offer a pretend send when email authentication is unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ emailCodeEnabled: false })))
  const { wrapper } = await open()
  await flushPromises()
  expect(wrapper.text()).toContain('邮箱登录暂未开放')
  expect(wrapper.find('input[type="email"]').exists()).toBe(false)
  wrapper.unmount()
})
it('sends once, disables resend during cooldown, and does not accept malformed codes', async () => {
  const fetch = vi.fn((path) =>
    Promise.resolve(
      Response.json(
        path === '/api/auth/methods'
          ? { emailCodeEnabled: true }
          : {
              challengeId: '11111111-1111-4111-8111-111111111111',
              expiresAt: new Date(Date.now() + 600000).toISOString(),
              resendAfterSeconds: 60,
            },
      ),
    ),
  )
  vi.stubGlobal('fetch', fetch)
  const { wrapper, session } = await open()
  const login = vi.spyOn(session, 'login').mockResolvedValue(false)
  await flushPromises()
  await wrapper.get('input[type="email"]').setValue('user@example.com')
  await wrapper.get('[data-test="send-code"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-test="send-code"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('验证码已发送')
  await wrapper.get('input[autocomplete="one-time-code"]').setValue('12')
  await wrapper.get('form').trigger('submit')
  expect(login).not.toHaveBeenCalled()
  await wrapper.get('input[autocomplete="one-time-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(login).toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111', '123456')
  wrapper.unmount()
})

const projectId = '11111111-1111-4111-8111-111111111111'
async function readyToVerify(returnTo?: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn((path) =>
      Promise.resolve(
        Response.json(
          path === '/api/auth/methods'
            ? { emailCodeEnabled: true }
            : {
                challengeId: projectId,
                expiresAt: new Date(Date.now() + 600000).toISOString(),
                resendAfterSeconds: 60,
              },
        ),
      ),
    ),
  )
  const context = await open(returnTo)
  const { wrapper, session } = context
  const drafts = useDraftStore()
  const login = vi.spyOn(session, 'login').mockImplementation(async () => {
    drafts.reset()
    session.revision++
    session.identity = { userId: 'account', isGuest: false, displayName: '账号' }
    return true
  })
  await flushPromises()
  await wrapper.get('#account-email').setValue('user@example.com')
  await wrapper.get('[data-test="send-code"]').trigger('click')
  await flushPromises()
  await wrapper.get('#account-code').setValue('123456')
  return { ...context, drafts, login }
}
it.each([undefined, '//evil.example', '/account'])(
  'returns direct or unsafe entry %s to projects',
  async (target) => {
    const { wrapper, router } = await readyToVerify(target)
    const replace = vi.spyOn(router, 'replace')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects')
    expect(replace).toHaveBeenCalled()
    wrapper.unmount()
  },
)
it('carries only this tab’s homepage idea through explicit login, not private project drafts', async () => {
  const { wrapper, router, drafts } = await readyToVerify('/')
  drafts.prompt = '做一个 2048 游戏'
  drafts.attach(projectId, '旧身份私有项目输入', 'old-key')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/')
  expect(drafts.prompt).toBe('做一个 2048 游戏')
  expect(drafts.projectDrafts).toEqual({})
  wrapper.unmount()
})
it.each([`/projects/${projectId}`, `/preview/${projectId}/${projectId}`])(
  'reauthorizes and returns to %s',
  async (target) => {
    const { wrapper, router } = await readyToVerify(target)
    const read = vi.spyOn(projects, 'getProject').mockResolvedValue({} as projects.Project)
    expect(wrapper.get('.account-back').attributes('href')).toBe(target)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(read).toHaveBeenCalledExactlyOnceWith(projectId)
    expect(router.currentRoute.value.path).toBe(target)
    wrapper.unmount()
  },
)
it('returns to projects with a notice if the new account cannot access the original project', async () => {
  const { wrapper, router } = await readyToVerify(`/projects/${projectId}`)
  vi.spyOn(projects, 'getProject').mockRejectedValue(new ApiFailure('NOT_FOUND', '不存在', 404))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/projects?notice=account-project-unavailable')
  wrapper.unmount()
})
it('keeps the signed-in account on a transient lookup failure and retries only the return', async () => {
  const { wrapper, router, login } = await readyToVerify(`/projects/${projectId}`)
  const read = vi
    .spyOn(projects, 'getProject')
    .mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '网络中断'))
    .mockResolvedValue({} as projects.Project)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/account')
  expect(wrapper.get('[role="alert"]').text()).toContain('登录已成功')
  await wrapper.get('[data-test="retry-return"]').trigger('click')
  await flushPromises()
  expect(read).toHaveBeenCalledTimes(2)
  expect(login).toHaveBeenCalledTimes(1)
  expect(router.currentRoute.value.path).toBe(`/projects/${projectId}`)
  wrapper.unmount()
})
it.each(['rejected', 'superseded'])('does not redirect a %s verification', async (outcome) => {
  const { wrapper, router, login } = await readyToVerify('/')
  if (outcome === 'rejected') login.mockRejectedValue(new ApiFailure('INVALID', '验证码无效', 422))
  else login.mockResolvedValue(false)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/account')
  wrapper.unmount()
})
it.each(['unmount', 'identity', 'route'])(
  'ignores a late project lookup after %s changes',
  async (change) => {
    const { wrapper, router, session } = await readyToVerify(`/projects/${projectId}`)
    let finish!: (value: projects.Project) => void
    vi.spyOn(projects, 'getProject').mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(finish).toBeDefined()
    if (change === 'unmount') wrapper.unmount()
    else if (change === 'identity') session.invalidate('其他标签页退出')
    else await router.push('/projects')
    const replace = vi.spyOn(router, 'replace')
    finish({} as projects.Project)
    await flushPromises()
    expect(replace).not.toHaveBeenCalled()
    if (change !== 'unmount') wrapper.unmount()
  },
)
it('does not redirect when another tab changes the identity on the account page', async () => {
  const { wrapper, router, session } = await readyToVerify('/')
  session.identity = { userId: 'other', isGuest: false, displayName: '账号' }
  session.revision++
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/account')
  expect(wrapper.text()).toContain('邮箱账号 · 已登录')
  wrapper.unmount()
})
it.each(['unmount', 'destination'])(
  'does not carry an idea or redirect after %s changes during verification',
  async (change) => {
    const { wrapper, router, session, drafts, login } = await readyToVerify('/')
    drafts.prompt = '离开前的输入'
    let finish!: (completed: boolean) => void
    login.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('form').trigger('submit')
    if (change === 'unmount') wrapper.unmount()
    else await router.push('/account?returnTo=/projects')
    const replace = vi.spyOn(router, 'replace')
    drafts.reset()
    session.identity = { userId: 'account', isGuest: false, displayName: '账号' }
    session.revision++
    finish(true)
    await flushPromises()
    expect(replace).not.toHaveBeenCalled()
    expect(drafts.prompt).toBe('')
    if (change !== 'unmount') wrapper.unmount()
  },
)
it('unlocks the account form if the identity changes during a return retry', async () => {
  const { wrapper, session } = await readyToVerify(`/projects/${projectId}`)
  const read = vi
    .spyOn(projects, 'getProject')
    .mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断开'))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  let finish!: (value: projects.Project) => void
  read.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await wrapper.get('[data-test="retry-return"]').trigger('click')
  session.invalidate('其他标签页退出')
  session.identity = { userId: 'new-guest', isGuest: true, displayName: '访客' }
  session.state = 'ready'
  finish({} as projects.Project)
  await flushPromises()
  expect(wrapper.get('#account-email').attributes('disabled')).toBeUndefined()
  expect(wrapper.text()).not.toContain('登录已成功')
  wrapper.unmount()
})
