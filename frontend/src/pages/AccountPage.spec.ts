import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, expect, it, vi } from 'vitest'
import AccountPage from './AccountPage.vue'
import { useSessionStore } from '../stores/session'
import { api } from '../api/client'

afterEach(() => {
  api.resetScope()
  vi.unstubAllGlobals()
})
function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.identity = { userId: 'guest', isGuest: true, displayName: '访客' }
  session.state = 'ready'
  api.setCsrf({ token: 'test', headerName: 'X-CSRF-TOKEN' })
  const wrapper = mount(AccountPage, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
  return { wrapper, session }
}
it('does not offer a pretend send when email authentication is unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ emailCodeEnabled: false })))
  const { wrapper } = open()
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
  const { wrapper, session } = open()
  const login = vi.spyOn(session, 'login').mockResolvedValue()
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
