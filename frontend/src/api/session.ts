import { api, isRecord, protocolFailure, type Csrf } from './client'

export type Identity = { userId: string; isGuest: boolean; displayName: string }
export function decodeIdentity(value: unknown): Identity {
  if (
    !isRecord(value) ||
    typeof value.userId !== 'string' ||
    typeof value.isGuest !== 'boolean' ||
    typeof value.displayName !== 'string'
  )
    return protocolFailure()
  return { userId: value.userId, isGuest: value.isGuest, displayName: value.displayName }
}
export async function refreshCsrf() {
  const token = await api.request('/api/csrf', (value): Csrf => {
    if (
      !isRecord(value) ||
      typeof value.token !== 'string' ||
      !value.token ||
      value.headerName !== 'X-CSRF-TOKEN'
    )
      return protocolFailure()
    return { token: value.token, headerName: value.headerName }
  })
  api.setCsrf(token)
}
export async function initializeSession(): Promise<Identity> {
  await refreshCsrf()
  const identity = await api.request('/api/session', decodeIdentity, { method: 'POST', body: {} })
  // Authentication rotates the CSRF token; keep it in memory, never browser storage.
  await refreshCsrf()
  return identity
}
