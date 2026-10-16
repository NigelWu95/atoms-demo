import { afterEach, expect, it, vi } from 'vitest'
import { api } from './client'
import { emailMethods, sendEmailCode, verifyEmailCode } from './emailAuth'

afterEach(() => { api.resetScope(); vi.unstubAllGlobals() })
it('uses strict challenge/identity contracts and sends no ownership claims', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ emailCodeEnabled: true }))
    .mockResolvedValueOnce(Response.json({ challengeId: '11111111-1111-4111-8111-111111111111', expiresAt: '2026-10-16T10:10:00Z', resendAfterSeconds: 60 }))
    .mockResolvedValueOnce(Response.json({ userId: 'account', isGuest: false, displayName: '邮箱创作者' }))
  vi.stubGlobal('fetch', fetch)
  api.setCsrf({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })
  expect(await emailMethods()).toEqual({ emailCodeEnabled: true })
  const sent = await sendEmailCode('user@example.com')
  expect(await verifyEmailCode(sent.challengeId, '123456')).toMatchObject({ isGuest: false })
  expect(JSON.parse(fetch.mock.calls[1]![1].body)).toEqual({ email: 'user@example.com' })
  expect(JSON.parse(fetch.mock.calls[2]![1].body)).toEqual({ challengeId: sent.challengeId, code: '123456' })
})
it('rejects malformed capabilities or incomplete send responses', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json({}))))
  api.setCsrf({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })
  await expect(emailMethods()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(sendEmailCode('user@example.com')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
