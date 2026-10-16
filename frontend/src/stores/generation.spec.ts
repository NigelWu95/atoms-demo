import { createPinia, setActivePinia } from 'pinia'
import { expect, it } from 'vitest'
import { useGenerationStore } from './generation'

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
