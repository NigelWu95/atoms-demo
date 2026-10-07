import { expect, it, vi } from 'vitest'
import { createPreviewContext } from './previewContext'
import { api } from '../api/client'
import { reactive } from 'vue'

it('snapshots Vue-backed initial data into cloneable JSON for the message port', async () => {
  const initial = reactive({ todos: [] as string[] })
  const context = createPreviewContext('project', 'version', 'HISTORY', initial)
  const state = await context({ type: 'STATE_GET', payload: {} }, 'key')
  expect(state.value).toEqual({ todos: [] })
  expect(() => structuredClone(state)).not.toThrow()
})

it('history reads only initialState, rejects writes and never calls HTTP', async () => {
  const request = vi.spyOn(api, 'request')
  const context = createPreviewContext('project', 'version', 'HISTORY', { count: 0 })
  const state = await context({ type: 'STATE_GET', payload: {} }, 'key')
  expect(state).toEqual({ value: { count: 0 }, revision: 0, stateSchemaVersion: 1 })
  await expect(
    context({ type: 'STATE_SAVE', payload: { value: {}, expectedRevision: 0 } }, 'key'),
  ).rejects.toMatchObject({ code: 'READ_ONLY_PREVIEW' })
  expect(request).not.toHaveBeenCalled()
})
it('binds current state to host project/version and preserves the retry key', async () => {
  const request = vi
    .spyOn(api, 'request')
    .mockResolvedValue({ value: {}, revision: 1, stateSchemaVersion: 1 })
  const context = createPreviewContext('project', 'version', 'CURRENT', {})
  await context(
    { type: 'STATE_SAVE', payload: { value: { count: 1 }, expectedRevision: 0 } },
    'stable-key',
  )
  expect(request.mock.calls[0]?.[0]).toBe('/api/projects/project/preview-state')
  expect(request.mock.calls[0]?.[2]).toEqual({
    method: 'PUT',
    idempotencyKey: 'stable-key',
    body: {
      loadedVersionId: 'version',
      expectedRevision: 0,
      stateSchemaVersion: 1,
      value: { count: 1 },
    },
  })
})
