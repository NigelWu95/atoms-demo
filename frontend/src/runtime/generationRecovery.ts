import type { CreateRun } from '../api/runs'
import { isRecord } from '../api/client'

export const RECOVERY_KEY = 'atoms.generation.recovery.v1'
export const RECOVERY_TTL = 24 * 60 * 60 * 1000
export type RecoveryScope = { ownerId: string; isGuest: boolean; stamp: string }
export type PendingGeneration = { key: string; body: CreateRun; preserveDraft: boolean }
export type RecoveryEntry = {
  projectId: string
  createdAt: number
  pending: PendingGeneration | null
  repairRunId: string | null
  expired: boolean
}
type Journal = { version: 1; scope: RecoveryScope; entries: RecoveryEntry[]; blocked: boolean }
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
const shape = (value: unknown, keys: string[]): value is Record<string, unknown> =>
  isRecord(value) &&
  Object.keys(value).length === keys.length &&
  keys.every((key) => Object.hasOwn(value, key))
function validScope(value: unknown): value is RecoveryScope {
  return (
    shape(value, ['ownerId', 'isGuest', 'stamp']) &&
    uuid(value.ownerId) &&
    typeof value.isGuest === 'boolean' &&
    typeof value.stamp === 'string' &&
    value.stamp.length <= 128
  )
}
export function sameRecoveryScope(a: RecoveryScope, b: RecoveryScope) {
  return a.ownerId === b.ownerId && a.isGuest === b.isGuest && a.stamp === b.stamp
}
function validPending(value: unknown): value is PendingGeneration {
  if (
    !shape(value, ['key', 'body', 'preserveDraft']) ||
    typeof value.key !== 'string' ||
    !/^[A-Za-z0-9._:-]{1,128}$/.test(value.key) ||
    typeof value.preserveDraft !== 'boolean'
  )
    return false
  const body = value.body
  return (
    shape(body, ['prompt', 'intent', 'baseVersionId', 'expectedProjectRevision']) &&
    typeof body.prompt === 'string' &&
    body.prompt.trim().length > 0 &&
    body.prompt.length <= 8000 &&
    !body.prompt.includes('\0') &&
    ((body.intent === 'CREATE' && body.baseVersionId === null) ||
      (body.intent === 'MODIFY' && uuid(body.baseVersionId))) &&
    typeof body.expectedProjectRevision === 'number' &&
    Number.isSafeInteger(body.expectedProjectRevision) &&
    body.expectedProjectRevision >= 0
  )
}
function validJournal(value: unknown, now: number): value is Journal {
  if (
    !shape(value, ['version', 'scope', 'entries', 'blocked']) ||
    value.version !== 1 ||
    !validScope(value.scope) ||
    typeof value.blocked !== 'boolean' ||
    !Array.isArray(value.entries) ||
    value.entries.length > 10
  )
    return false
  const projects = new Set<string>()
  return value.entries.every((entry) => {
    if (
      !shape(entry, ['projectId', 'createdAt', 'pending', 'repairRunId', 'expired']) ||
      !uuid(entry.projectId) ||
      projects.has(entry.projectId) ||
      typeof entry.createdAt !== 'number' ||
      !Number.isSafeInteger(entry.createdAt) ||
      entry.createdAt < 0 ||
      entry.createdAt > now ||
      !(entry.pending === null || validPending(entry.pending)) ||
      !(entry.repairRunId === null || uuid(entry.repairRunId)) ||
      typeof entry.expired !== 'boolean' ||
      (entry.expired && (entry.pending !== null || entry.repairRunId !== null))
    )
      return false
    projects.add(entry.projectId)
    return entry.expired || entry.pending !== null || entry.repairRunId !== null
  })
}
export function expireEntries(entries: RecoveryEntry[], now = Date.now()): RecoveryEntry[] {
  return entries.flatMap((entry) => {
    if (now < entry.createdAt + RECOVERY_TTL || entry.expired) return [entry]
    return entry.pending ? [{ ...entry, pending: null, repairRunId: null, expired: true }] : []
  })
}
export function clearRecovery(): boolean {
  try {
    sessionStorage.removeItem(RECOVERY_KEY)
    return true
  } catch {
    return false
  }
}
export function recoveryStamp(): string | null {
  try {
    const stamp = localStorage.getItem('atoms.session.changed') ?? ''
    return stamp.length <= 128 ? stamp : null
  } catch {
    return null
  }
}
export function writeRecovery(
  scope: RecoveryScope,
  entries: RecoveryEntry[],
  blocked = false,
  now = Date.now(),
): boolean {
  const journal: Journal = { version: 1, scope, entries, blocked }
  if (!validJournal(journal, now)) return false
  const raw = JSON.stringify(journal)
  if (raw.length > 256000) return false
  try {
    sessionStorage.setItem(RECOVERY_KEY, raw)
    return true
  } catch {
    return false
  }
}
export function readRecovery(scope: RecoveryScope, now = Date.now()) {
  const empty = { entries: [] as RecoveryEntry[], blocked: false, unavailable: false }
  try {
    const raw = sessionStorage.getItem(RECOVERY_KEY)
    if (raw === null) return empty
    let value: unknown
    try {
      value = raw.length <= 256000 ? JSON.parse(raw) : null
    } catch {
      value = null
    }
    if (!validJournal(value, now)) {
      const saved = writeRecovery(scope, [], true, now)
      if (!saved) clearRecovery()
      return { ...empty, blocked: true, unavailable: !saved }
    }
    if (!sameRecoveryScope(value.scope, scope)) {
      return { ...empty, unavailable: !clearRecovery() }
    }
    const entries = expireEntries(value.entries, now)
    const unavailable = !writeRecovery(scope, entries, value.blocked, now)
    if (unavailable) clearRecovery()
    return { entries, blocked: value.blocked, unavailable }
  } catch {
    return { ...empty, blocked: true, unavailable: true }
  }
}
