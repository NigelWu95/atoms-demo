import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AccountIdentity from './AccountIdentity.vue'
import { getAccount, type AccountProfile } from '../api/account'

vi.mock('../api/account', () => ({ getAccount: vi.fn() }))
const identity = { userId: 'owner-a', isGuest: false, displayName: '邮箱创作者' }
const profile = {
  ...identity,
  email: 'creator@example.com',
  emailVerified: true as const,
  createdAt: '2026-10-18T00:00:00Z',
}
const wrappers: ReturnType<typeof mount>[] = []
function open() {
  const wrapper = mount(AccountIdentity, { props: { identity } })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => vi.resetAllMocks())
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))

it('shows the verified email as text and offers its complete value', async () => {
  vi.mocked(getAccount).mockResolvedValue(profile)
  const wrapper = open()
  await flushPromises()
  expect(wrapper.get('summary').text()).toBe(profile.email)
  expect(wrapper.get('.full-email').text()).toBe(profile.email)
})

it('discards a late email after switching to a guest and aborts the old request', async () => {
  let finish!: (value: AccountProfile) => void
  vi.mocked(getAccount).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const wrapper = open()
  const signal = vi.mocked(getAccount).mock.calls[0]![0]!
  await wrapper.setProps({ identity: { userId: 'guest', isGuest: true, displayName: '访客' } })
  finish(profile)
  await flushPromises()
  expect(signal.aborted).toBe(true)
  expect(wrapper.text()).toBe('访客')
  expect(getAccount).toHaveBeenCalledTimes(1)
})

it('rejects mismatched owner data and offers an explicit read-only retry', async () => {
  vi.mocked(getAccount)
    .mockResolvedValueOnce({ ...profile, userId: 'other' })
    .mockResolvedValueOnce(profile)
  const wrapper = open()
  await flushPromises()
  expect(wrapper.text()).not.toContain(profile.email)
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('summary').text()).toBe(profile.email)
})

it('does not resurrect the prior email when two accounts finish out of order', async () => {
  let finish!: (value: AccountProfile) => void
  vi.mocked(getAccount)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    .mockResolvedValueOnce({ ...profile, userId: 'owner-b', email: 'new@example.com' })
  const wrapper = open()
  await wrapper.setProps({ identity: { ...identity, userId: 'owner-b' } })
  await flushPromises()
  finish(profile)
  await flushPromises()
  expect(wrapper.get('summary').text()).toBe('new@example.com')
})
