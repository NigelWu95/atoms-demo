import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useEngineeringWorkbench } from './useEngineeringWorkbench'
import { useSessionStore } from '../stores/session'
import { ApiFailure } from '../api/client'

const { read, write, capabilities, getProject } = vi.hoisted(() => ({
  read: vi.fn(),
  write: vi.fn(),
  capabilities: vi.fn(),
  getProject: vi.fn(),
}))
vi.mock('../api/projects', () => ({ getProject }))
vi.mock('../api/projectWorkbench', async (original) => ({
  ...(await original<typeof import('../api/projectWorkbench')>()),
  read,
  write,
  capabilities,
}))
let initialized = false
beforeEach(() => {
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.state = 'ready'
  session.identity = {
    userId: 'guest',
    displayName: '访客',
    csrf: { token: 'x', headerName: 'X-CSRF-TOKEN' },
  } as never
  initialized = false
  getProject.mockResolvedValue({ id: 'project', ownerId: 'guest', archived: false })
  capabilities.mockResolvedValue({
    enabled: true,
    agentEnabled: true,
    sourceReady: true,
    archived: false,
  })
  read.mockImplementation(async (_project, path) => {
    if (path === '/source/head')
      return {
        projectId: 'project',
        sourceVersionId: initialized ? 'v1' : null,
        revision: initialized ? 1 : 0,
        status: 'READY',
      }
    if (path === '/source/versions') return { items: [], nextBeforeVersion: null }
    if (['/builds', '/preparations', '/runs'].includes(path)) return { items: [], nextCursor: null }
    if (path === '/runtime') return { databaseBound: false, coordinatorReady: true }
    return []
  })
})
afterEach(() => vi.resetAllMocks())
function setup() {
  let state!: ReturnType<typeof useEngineeringWorkbench>
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useEngineeringWorkbench(() => 'project')
        return () => null
      },
    }),
  )
  return {
    get state() {
      return state
    },
    wrapper,
  }
}

it('first guest submission initializes a full project and submits exactly one task', async () => {
  write.mockImplementation(async (_project, path) => {
    if (path === '/source/initialize') {
      initialized = true
      return { id: 'operation', status: 'COMMITTED' }
    }
    return { id: 'task', status: 'QUEUED' }
  })
  const { state, wrapper } = setup()
  await flushPromises()
  expect(state.canSubmit.value).toBe(true)
  expect(state.canEdit.value).toBe(false)
  const accepted = vi.fn()
  await state.submit('实现真实后端', accepted)
  expect(write.mock.calls.map((call) => call[1])).toEqual(['/source/initialize', '/agent/tasks'])
  expect(write.mock.calls[1]![2]).toEqual({
    prompt: '实现真实后端',
    baseSourceVersionId: 'v1',
    expectedSourceRevision: 1,
  })
  expect(accepted).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it('retains the draft and same task key/base when an accepted response is lost', async () => {
  let submissions = 0
  write.mockImplementation(async (_project, path) => {
    if (path === '/source/initialize') {
      initialized = true
      return { id: 'operation', status: 'COMMITTED' }
    }
    if (++submissions === 1) throw new ApiFailure('NETWORK_ERROR', '响应中断')
    return { id: 'task', status: 'QUEUED' }
  })
  const { state, wrapper } = setup()
  await flushPromises()
  const accepted = vi.fn()
  await state.submit('保存需求', accepted)
  expect(accepted).not.toHaveBeenCalled()
  await state.intent.retry()
  expect(write.mock.calls.filter((call) => call[1] === '/source/initialize')).toHaveLength(1)
  const submitted = write.mock.calls.filter((call) => call[1] === '/agent/tasks')
  expect(submitted[1]![2]).toEqual(submitted[0]![2])
  expect(submitted[1]![3]).toEqual(submitted[0]![3])
  expect(accepted).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it('does not submit after identity changes during initialization', async () => {
  let finish!: (value: unknown) => void
  write.mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const { state, wrapper } = setup()
  await flushPromises()
  const accepted = vi.fn(),
    submitted = state.submit('旧身份需求', accepted)
  useSessionStore().identity = null
  await flushPromises()
  finish({ id: 'operation', status: 'COMMITTED' })
  await submitted
  expect(write).toHaveBeenCalledOnce()
  expect(accepted).not.toHaveBeenCalled()
  wrapper.unmount()
})
