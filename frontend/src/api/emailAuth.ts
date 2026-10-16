import { api, isRecord, protocolFailure } from './client'
import { decodeIdentity } from './session'

export type EmailChallenge = { challengeId: string; expiresAt: string; resendAfterSeconds: number }
export function emailMethods() {
  return api.request('/api/auth/methods', (value) => {
    if (!isRecord(value) || typeof value.emailCodeEnabled !== 'boolean') return protocolFailure()
    return { emailCodeEnabled: value.emailCodeEnabled }
  })
}
export function sendEmailCode(email: string, signal?: AbortSignal): Promise<EmailChallenge> {
  return api.request(
    '/api/auth/email/challenges',
    (value) => {
      if (
        !isRecord(value) ||
        typeof value.challengeId !== 'string' ||
        !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value.challengeId) ||
        typeof value.expiresAt !== 'string' ||
        !Number.isFinite(Date.parse(value.expiresAt)) ||
        typeof value.resendAfterSeconds !== 'number' ||
        !Number.isInteger(value.resendAfterSeconds) ||
        value.resendAfterSeconds < 1 ||
        value.resendAfterSeconds > 86400
      )
        return protocolFailure()
      return {
        challengeId: value.challengeId,
        expiresAt: value.expiresAt,
        resendAfterSeconds: value.resendAfterSeconds,
      }
    },
    { method: 'POST', body: { email }, signal, timeoutMs: 30000 },
  )
}
export function verifyEmailCode(challengeId: string, code: string) {
  return api.request('/api/auth/email/verifications', decodeIdentity, {
    method: 'POST',
    body: { challengeId, code },
  })
}
