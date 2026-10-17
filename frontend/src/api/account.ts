import { api, isRecord, protocolFailure } from './client'
import { decodeIdentity, type Identity } from './session'

export type AccountProfile = Identity & { email?: string; emailVerified?: true; createdAt?: string }
export function getAccount(signal?: AbortSignal): Promise<AccountProfile> {
  return api.request(
    '/api/account',
    (value) => {
      const identity = decodeIdentity(value)
      if (identity.isGuest) return identity
      if (
        !isRecord(value) ||
        typeof value.email !== 'string' ||
        !value.email ||
        value.emailVerified !== true ||
        typeof value.createdAt !== 'string' ||
        !Number.isFinite(Date.parse(value.createdAt))
      )
        return protocolFailure()
      return { ...identity, email: value.email, emailVerified: true, createdAt: value.createdAt }
    },
    { signal },
  )
}
