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
