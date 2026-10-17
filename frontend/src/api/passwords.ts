import { api, isRecord, protocolFailure } from './client'
import { decodeIdentity } from './session'
import { decodeChallenge } from './emailAuth'

export type PasswordUpdate =
  | { mode: 'setup'; challengeId: string; code: string; newPassword: string }
  | { mode: 'change'; currentPassword: string; newPassword: string }
export type PasswordStatus = { configured: boolean; updatedAt: string | null }
function decodeStatus(value: unknown): PasswordStatus {
  if (
    !isRecord(value) ||
    typeof value.configured !== 'boolean' ||
    (value.configured
      ? typeof value.updatedAt !== 'string' || !Number.isFinite(Date.parse(value.updatedAt))
      : value.updatedAt !== null)
  )
    return protocolFailure()
  return { configured: value.configured, updatedAt: value.updatedAt as string | null }
}
export function getPasswordStatus(signal?: AbortSignal) {
  return api.request('/api/account/password', decodeStatus, { signal })
}
export function sendPasswordCode(signal?: AbortSignal) {
  return api.request('/api/account/password/challenges', decodeChallenge, {
    method: 'POST',
    body: {},
    signal,
    timeoutMs: 30000,
  })
}
export function verifyPassword(email: string, password: string) {
  return api.request('/api/auth/password/verifications', decodeIdentity, {
    method: 'POST',
    body: { email, password },
  })
}
export function updatePassword(input: PasswordUpdate) {
  return api.request('/api/account/password', decodeStatus, { method: 'PUT', body: input })
}
export function passwordValid(value: string) {
  return (
    [...value].length >= 15 &&
    [...value].length <= 64 &&
    new TextEncoder().encode(value).length <= 72 &&
    !/\u0000|[\uD800-\uDFFF]/u.test(value)
  )
}
