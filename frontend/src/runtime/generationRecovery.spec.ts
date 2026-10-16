import { beforeEach, expect, it, vi } from 'vitest'
import {
  RECOVERY_KEY,
  RECOVERY_TTL,
  readRecovery,
  writeRecovery,
  type RecoveryEntry,
} from './generationRecovery'

const now = 1800000000000
const scope = { ownerId: '11111111-1111-4111-8111-111111111111', isGuest: true, stamp: '' }
const projectId = '22222222-2222-4222-8222-222222222222'
const entry = (): RecoveryEntry => ({
  projectId,
  createdAt: now,
  pending: {
    key: 'original-key',
    body: { prompt: '原需求', intent: 'CREATE', baseVersionId: null, expectedProjectRevision: 0 },
    preserveDraft: false,
  },
  repairRunId: null,
  expired: false,
})
beforeEach(() => {
  vi.restoreAllMocks()
  sessionStorage.clear()
})

it('round trips the exact request without extending its lifetime', () => {
  const original = entry()
  expect(writeRecovery(scope, [original], false, now)).toBe(true)
  expect(readRecovery(scope, now + RECOVERY_TTL - 1).entries).toEqual([original])
  expect(readRecovery(scope, now + RECOVERY_TTL).entries).toEqual([
    { ...original, pending: null, repairRunId: null, expired: true },
  ])
  expect(sessionStorage.getItem(RECOVERY_KEY)).not.toContain('原需求')
})

it('does not return another owner, guest type or account epoch', () => {
  for (const other of [
    { ...scope, ownerId: projectId },
    { ...scope, isGuest: false },
    { ...scope, stamp: 'changed' },
  ]) {
    writeRecovery(scope, [entry()], false, now)
    expect(readRecovery(other, now).entries).toEqual([])
    expect(sessionStorage.getItem(RECOVERY_KEY)).toBeNull()
  }
})

it('turns corrupt, oversized or extra-field caches into a content-free blocking notice', () => {
  for (const raw of [
    '{',
    'x'.repeat(256001),
    JSON.stringify({ version: 1, cookie: 'NEVER_KEEP' }),
  ]) {
    sessionStorage.setItem(RECOVERY_KEY, raw)
    const result = readRecovery(scope, now)
    expect(result.blocked).toBe(true)
    expect(result.entries).toEqual([])
    expect(sessionStorage.getItem(RECOVERY_KEY)).not.toContain('NEVER_KEEP')
  }
})

it('rejects invalid request fields without overwriting an existing uncertain request', () => {
  writeRecovery(scope, [entry()], false, now)
  const before = sessionStorage.getItem(RECOVERY_KEY)
  for (const body of [
    { ...entry().pending!.body, prompt: '' },
    { ...entry().pending!.body, prompt: 'x'.repeat(8001) },
    { ...entry().pending!.body, expectedProjectRevision: -1 },
    { ...entry().pending!.body, extra: 'PRIVATE' },
    { ...entry().pending!.body, intent: 'MODIFY', baseVersionId: null },
  ]) {
    expect(
      writeRecovery(
        scope,
        [{ ...entry(), pending: { ...entry().pending!, body } } as RecoveryEntry],
        false,
        now,
      ),
    ).toBe(false)
    expect(sessionStorage.getItem(RECOVERY_KEY)).toBe(before)
  }
})

it('caps projects without evicting or replacing pending entries', () => {
  const entries = Array.from({ length: 10 }, (_, i) => ({
    ...entry(),
    projectId: `22222222-2222-4222-8222-${String(i).padStart(12, '0')}`,
  }))
  expect(writeRecovery(scope, entries, false, now)).toBe(true)
  const before = sessionStorage.getItem(RECOVERY_KEY)
  expect(writeRecovery(scope, [...entries, entry()], false, now)).toBe(false)
  expect(sessionStorage.getItem(RECOVERY_KEY)).toBe(before)
})

it('expires repair-only markers and rejects duplicate projects and future timestamps', () => {
  const marker = { ...entry(), pending: null, repairRunId: projectId }
  writeRecovery(scope, [marker], false, now)
  expect(readRecovery(scope, now + RECOVERY_TTL).entries).toEqual([])
  expect(writeRecovery(scope, [entry(), entry()], false, now)).toBe(false)
  expect(writeRecovery(scope, [{ ...entry(), createdAt: now + 1 }], false, now)).toBe(false)
})

it('reports unavailable storage without throwing or pretending to save', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('PRIVATE')
  })
  expect(readRecovery(scope, now).unavailable).toBe(true)
  expect(readRecovery(scope, now).blocked).toBe(true)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('PRIVATE')
  })
  expect(writeRecovery(scope, [entry()], false, now)).toBe(false)
})

it('removes expired text when rewriting fails but deletion remains available', () => {
  writeRecovery(scope, [entry()], false, now)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('denied')
  })
  const result = readRecovery(scope, now + RECOVERY_TTL)
  expect(result.entries[0]?.expired).toBe(true)
  expect(result.unavailable).toBe(true)
  expect(sessionStorage.getItem(RECOVERY_KEY)).toBeNull()
})
