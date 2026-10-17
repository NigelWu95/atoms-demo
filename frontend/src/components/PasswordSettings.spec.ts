import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PasswordSettings from './PasswordSettings.vue'
import { api } from '../api/client'
import type { PasswordStatus } from '../api/passwords'
import { useSessionStore } from '../stores/session'

const email = 'creator@example.com'
const challengeId = '11111111-1111-4111-8111-111111111111'
const newPassword = 'a safe new password phrase'
const updatedAt = '2026-10-17T04:05:06Z'

afterEach(() => {
  api.resetScope()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function open(configured = false) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.identity = {
    userId: '22222222-2222-4222-8222-222222222222',
    isGuest: false,
    displayName: '邮箱创作者',
  }
  session.state = 'ready'
  session.hasConnected = true
  api.setCsrf({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })
  let status: PasswordStatus = { configured, updatedAt: configured ? updatedAt : null }
  const fetch = vi.fn((path: string, options?: RequestInit) => {
    if (path === '/api/account/password') return Promise.resolve(Response.json(status))
    if (path === '/api/auth/methods')
      return Promise.resolve(Response.json({ emailCodeEnabled: true, passwordEnabled: true }))
    if (path === '/api/account/password/challenges' && options?.method === 'POST')
      return Promise.resolve(
        Response.json(
          {
            challengeId,
            expiresAt: new Date(Date.now() + 600000).toISOString(),
            resendAfterSeconds: 60,
          },
          { status: 201 },
        ),
      )
    if (path === '/api/me') return Promise.resolve(Response.json({ ...session.identity }))
    if (path === '/api/csrf')
      return Promise.resolve(Response.json({ token: 'renewed-test', headerName: 'X-CSRF-TOKEN' }))
    throw new Error(`Unexpected test endpoint: ${path}`)
  })
  vi.stubGlobal('fetch', fetch)
  const changePassword = vi.spyOn(session, 'changePassword').mockImplementation(async () => {
    status = { configured: true, updatedAt }
    return status
  })
  const wrapper = mount(PasswordSettings, { props: { email }, global: { plugins: [pinia] } })
  await flushPromises()
  return { wrapper, session, fetch, changePassword }
}

describe('account password management', () => {
  it('requires an explicit send to the bound email before first-time password setup', async () => {
    const { wrapper, fetch } = await open()
    try {
      expect(wrapper.text()).toContain('设置密码')
      expect(wrapper.text()).toContain(email)
      expect(fetch.mock.calls.some(([path]) => path.endsWith('/challenges'))).toBe(false)
      expect(wrapper.find('#current-password').exists()).toBe(false)

      await wrapper.get('[data-test="send-password-code"]').trigger('click')
      await flushPromises()

      expect(fetch).toHaveBeenCalledWith(
        '/api/account/password/challenges',
        expect.objectContaining({ method: 'POST', body: '{}' }),
      )
      expect(wrapper.find('#password-code').exists()).toBe(true)
      expect(wrapper.get('[data-test="send-password-code"]').attributes('disabled')).toBeDefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('sets a password with the dedicated code and clears secret fields after success', async () => {
    const { wrapper, changePassword } = await open()
    try {
      await wrapper.get('[data-test="send-password-code"]').trigger('click')
      await flushPromises()
      await wrapper.get('#password-code').setValue('123456')
      await wrapper.get('#new-password').setValue(newPassword)
      await wrapper.get('#confirm-password').setValue(newPassword)
      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(changePassword).toHaveBeenCalledExactlyOnceWith({
        mode: 'setup',
        challengeId,
        code: '123456',
        newPassword,
      })
      expect(wrapper.find('#password-code').exists()).toBe(false)
      expect(wrapper.find('#current-password').exists()).toBe(true)
      expect(wrapper.get<HTMLInputElement>('#new-password').element.value).toBe('')
      expect(wrapper.get<HTMLInputElement>('#confirm-password').element.value).toBe('')
    } finally {
      wrapper.unmount()
    }
  })

  it('changes an existing password using the exact current password without sending email', async () => {
    const { wrapper, fetch, changePassword } = await open(true)
    const currentPassword = ' original password with spaces '
    try {
      expect(wrapper.text()).toContain('修改密码')
      await wrapper.get('#current-password').setValue(currentPassword)
      await wrapper.get('#new-password').setValue(newPassword)
      await wrapper.get('#confirm-password').setValue(newPassword)
      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(changePassword).toHaveBeenCalledExactlyOnceWith({
        mode: 'change',
        currentPassword,
        newPassword,
      })
      expect(fetch.mock.calls.some(([path]) => path.endsWith('/challenges'))).toBe(false)
      for (const id of ['current-password', 'new-password', 'confirm-password'])
        expect(wrapper.get<HTMLInputElement>(`#${id}`).element.value).toBe('')
    } finally {
      wrapper.unmount()
    }
  })

  it('does not submit when the confirmation differs from the new password', async () => {
    const { wrapper, changePassword } = await open(true)
    try {
      await wrapper.get('#current-password').setValue('original password phrase')
      await wrapper.get('#new-password').setValue(newPassword)
      await wrapper.get('#confirm-password').setValue(`${newPassword} different`)
      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(changePassword).not.toHaveBeenCalled()
      expect(wrapper.text()).toMatch(/一致|相同/)
    } finally {
      wrapper.unmount()
    }
  })

  it.each([
    ['short', 'short password'],
    ['too many code points', 'x'.repeat(65)],
    ['too many UTF-8 bytes', '密'.repeat(25)],
  ])('does not submit a new password that is %s', async (_reason, password) => {
    const { wrapper, changePassword } = await open(true)
    try {
      await wrapper.get('#current-password').setValue('original password phrase')
      await wrapper.get('#new-password').setValue(password)
      await wrapper.get('#confirm-password').setValue(password)
      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(changePassword).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })

  it('preserves the setup challenge and entered secrets across same-account reconciliation', async () => {
    const { wrapper, session, fetch, changePassword } = await open()
    try {
      await wrapper.get('[data-test="send-password-code"]').trigger('click')
      await flushPromises()
      await wrapper.get('#password-code').setValue('123456')
      await wrapper.get('#new-password').setValue(newPassword)
      await wrapper.get('#confirm-password').setValue(newPassword)
      const codeInput = wrapper.get('#password-code').element

      // Returning from the mailbox invokes this same session reconciliation path.
      for (let visit = 0; visit < 2; visit++) {
        await session.reconcile()
        await flushPromises()
        expect(wrapper.get('#password-code').element).toBe(codeInput)
        expect(wrapper.get<HTMLInputElement>('#password-code').element.value).toBe('123456')
        expect(wrapper.get<HTMLInputElement>('#new-password').element.value).toBe(newPassword)
        expect(wrapper.get<HTMLInputElement>('#confirm-password').element.value).toBe(newPassword)
      }
      expect(fetch.mock.calls.filter(([path]) => path.endsWith('/challenges'))).toHaveLength(1)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(changePassword).toHaveBeenCalledExactlyOnceWith({
        mode: 'setup',
        challengeId,
        code: '123456',
        newPassword,
      })
    } finally {
      wrapper.unmount()
    }
  })
})
