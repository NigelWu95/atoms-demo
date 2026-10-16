import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useGenerationStore } from './generation'
import { RECOVERY_KEY, RECOVERY_TTL, writeRecovery } from '../runtime/generationRecovery'

const identity = { userId: '11111111-1111-4111-8111-111111111111', isGuest: true }
const projectId = '22222222-2222-4222-8222-222222222222'
const baseVersionId = '33333333-3333-4333-8333-333333333333'
const repairId = '44444444-4444-4444-8444-444444444444'
const request = {
  prompt: '修复需求',
  intent: 'MODIFY' as const,
  baseVersionId,
  expectedProjectRevision: 1,
}
beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
function restoredStore() {
  setActivePinia(createPinia())
  return useGenerationStore()
}

it('rejects an eleventh pending project without losing the original ten', () => {
  const store = restoredStore()
  store.bind(identity)
  for (let index = 0; index < 10; index++) {
    const id = `22222222-2222-4222-8222-${String(index).padStart(12, '0')}`
    store.activate(id, identity.userId)
    store.intent(id, request)
  }
  const saved = sessionStorage.getItem(RECOVERY_KEY)
  store.activate(projectId, identity.userId)
  expect(() => store.intent(projectId, request)).toThrow('上限')
  expect(Object.keys(store.pending)).toHaveLength(10)
  expect(sessionStorage.getItem(RECOVERY_KEY)).toBe(saved)
  store.$dispose()
})

it('restores only after identity and project authorization, keeping the original request', () => {
  const first = restoredStore()
  first.bind(identity)
  first.activate(projectId, identity.userId)
  const original = first.intent(projectId, request, true)
  first.$dispose()
  const second = restoredStore()
  expect(second.pending).toEqual({})
  second.bind(identity)
  expect(second.pending).toEqual({})
  expect(second.activate(projectId, 'another-owner')).toBe(false)
  expect(second.pending).toEqual({})
  expect(second.activate(projectId, identity.userId)).toBe(true)
  expect(second.pending[projectId]).toEqual(original)
  expect(second.intent(projectId, { ...request, prompt: '新需求' })).toEqual(original)
  second.resolved(projectId, repairId)
  expect(sessionStorage.getItem(RECOVERY_KEY)).not.toContain('修复需求')
  second.$dispose()
  const third = restoredStore()
  third.bind(identity)
  third.activate(projectId, identity.userId)
  expect(third.canRestoreDraft(projectId, repairId)).toBe(false)
  third.reset()
  expect(sessionStorage.getItem(RECOVERY_KEY)).toBeNull()
  third.$dispose()
})

it('clears expired text on the active page and blocks replacement until explicit discard', () => {
  vi.useFakeTimers()
  const store = restoredStore()
  store.bind(identity)
  store.activate(projectId, identity.userId)
  store.intent(projectId, request, true)
  vi.advanceTimersByTime(RECOVERY_TTL)
  expect(store.pending[projectId]).toBeUndefined()
  expect(store.isBlocked(projectId)).toBe(true)
  expect(sessionStorage.getItem(RECOVERY_KEY)).not.toContain('修复需求')
  expect(() => store.intent(projectId, request)).toThrow()
  store.discard(projectId)
  expect(store.isBlocked(projectId)).toBe(false)
  store.$dispose()
})

it('refuses a changed account epoch even if the user id remains the same', () => {
  const store = restoredStore()
  store.bind(identity)
  store.activate(projectId, identity.userId)
  store.intent(projectId, request)
  localStorage.setItem('atoms.session.changed', 'new-login')
  expect(store.authorized(projectId, identity.userId)).toBe(false)
  expect(store.pending).toEqual({})
  expect(sessionStorage.getItem(RECOVERY_KEY)).toBeNull()
  store.$dispose()
})

it('blocks corrupt cache and retains in-memory exact retry on write failure', () => {
  sessionStorage.setItem(RECOVERY_KEY, '{')
  const store = restoredStore()
  store.bind(identity)
  store.activate(projectId, identity.userId)
  expect(store.isBlocked(projectId)).toBe(true)
  store.discard(projectId)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('denied')
  })
  const original = store.intent(projectId, request)
  expect(store.storageUnavailable).toBe(true)
  expect(store.intent(projectId, { ...request, prompt: '不同内容' })).toEqual(original)
  store.$dispose()
})

it('keeps a verified live request when an identity recheck cannot read storage', () => {
  const store = restoredStore()
  store.bind(identity)
  store.activate(projectId, identity.userId)
  const original = store.intent(projectId, request, true)
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('denied')
  })
  store.bind(identity)
  expect(store.pending[projectId]).toEqual(original)
  expect(store.authorized(projectId, identity.userId)).toBe(true)
  expect(store.storageUnavailable).toBe(true)
  store.$dispose()
})

it('does not load a stale cache when session invalidation is followed by a late bind', () => {
  writeRecovery({ ownerId: identity.userId, isGuest: true, stamp: '' }, [
    {
      projectId,
      createdAt: Date.now(),
      pending: { key: 'key', body: request, preserveDraft: true },
      repairRunId: null,
      expired: false,
    },
  ])
  const store = restoredStore()
  store.reset()
  store.bind(identity)
  store.activate(projectId, identity.userId)
  expect(store.pending).toEqual({})
  store.$dispose()
})

it('retains the original uncertain intent even when draft or version changes', () => {
  setActivePinia(createPinia())
  const store = useGenerationStore()
  const body = {
    prompt: '第一条需求',
    intent: 'CREATE' as const,
    baseVersionId: null,
    expectedProjectRevision: 0,
  }
  const first = store.intent('project', body)
  body.prompt = '外部修改'
  expect(store.intent('project', { ...body, expectedProjectRevision: 2 })).toEqual(first)
  expect(first.body.prompt).toBe('第一条需求')
  store.resolved('project')
  expect(store.intent('project', body).key).not.toBe(first.key)
})

it('retains repair draft protection on retries and clears it with normal runs or session reset', () => {
  setActivePinia(createPinia())
  const store = useGenerationStore()
  const body = {
    prompt: '修复需求',
    intent: 'MODIFY' as const,
    baseVersionId: 'v1',
    expectedProjectRevision: 1,
  }
  const first = store.intent('project', body, true)
  expect(store.intent('project', { ...body, prompt: '新需求' }).preserveDraft).toBe(true)
  expect(store.canRestoreDraft('project', 'any-run')).toBe(false)
  store.resolved('project', 'repair-run')
  expect(store.canRestoreDraft('project', 'repair-run')).toBe(false)
  expect(store.canRestoreDraft('project', 'other-run')).toBe(true)
  const normal = store.intent('project', body)
  expect(normal.key).not.toBe(first.key)
  expect(normal.preserveDraft).toBe(false)
  store.resolved('project', 'normal-run')
  expect(store.canRestoreDraft('project', 'repair-run')).toBe(true)
  store.intent('project', body, true)
  store.resolved('project', 'repair-run')
  store.reset()
  expect(store.pending).toEqual({})
  expect(store.canRestoreDraft('project', 'repair-run')).toBe(true)
})
