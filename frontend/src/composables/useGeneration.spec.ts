import { createPinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getProject, type Project } from '../api/projects'
import { generationAvailability, listMessages, listRuns, submitRun, type Run } from '../api/runs'
import { useGeneration } from './useGeneration'
import { subscribeToRun } from '../api/runEvents'

vi.mock('../api/runEvents', () => ({ subscribeToRun: vi.fn(() => vi.fn()) }))

vi.mock('../api/projects', () => ({ getProject: vi.fn() }))
vi.mock('../api/runs', async (original) => ({
  ...(await original<typeof import('../api/runs')>()),
  generationAvailability: vi.fn(),
  listRuns: vi.fn(),
  listMessages: vi.fn(),
  submitRun: vi.fn(),
  cancelRun: vi.fn(),
}))
const initial: Project = {
  id: 'project-1',
  name: 'App',
  ownerId: 'owner-1',
  projectRevision: 0,
  currentVersionId: null,
  createdAt: '2026-10-07T00:00:00Z',
  updatedAt: '2026-10-07T00:00:00Z',
}
const task: Run = {
  id: 'run-1',
  projectId: initial.id,
  prompt: '创建计数器',
  status: 'FAILED',
  stage: 'FAILED',
  resultVersionId: null,
  errorCode: 'MODEL_UNAVAILABLE',
  errorMessage: '模型暂不可用',
  lastEventSeq: 3,
}
const wrappers: ReturnType<typeof mount>[] = []
function setup(draft = '') {
  const project = ref(initial)
  const prompt = ref(draft)
  let generation!: ReturnType<typeof useGeneration>
  wrappers.push(
    mount(
      defineComponent({
        setup() {
          generation = useGeneration(
            () => project.value,
            () => prompt.value,
            (value) => {
              prompt.value = value
            },
            (value) => {
              project.value = value
            },
          )
          return () => null
        },
      }),
      { global: { plugins: [createPinia()] } },
    ),
  )
  return { generation, project, prompt }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(subscribeToRun).mockImplementation(() => vi.fn())
  vi.mocked(generationAvailability).mockResolvedValue({
    configured: true,
    workerEnabled: true,
    remainingRequests: 2,
    model: 'deepseek-flash',
  })
  vi.mocked(listRuns).mockResolvedValue({ items: [task], nextCursor: null })
  vi.mocked(listMessages).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(getProject).mockResolvedValue(initial)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})

it('reads the project after the terminal run, so completion cannot strand an old preview', async () => {
  let finish!: (value: { items: Run[]; nextCursor: null }) => void
  vi.mocked(listRuns).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const { project } = setup()
  await flushPromises()
  expect(getProject).not.toHaveBeenCalled()
  vi.mocked(getProject).mockResolvedValue({
    ...initial,
    currentVersionId: 'version-1',
    projectRevision: 1,
  })
  finish({
    items: [{ ...task, status: 'SUCCEEDED', resultVersionId: 'version-1' }],
    nextCursor: null,
  })
  await flushPromises()
  expect(project.value.currentVersionId).toBe('version-1')
})
it('restores a failed request for editing without overwriting a newer draft', async () => {
  const empty = setup()
  const edited = setup('加入筛选')
  await flushPromises()
  expect(empty.prompt.value).toBe(task.prompt)
  expect(edited.prompt.value).toBe('加入筛选')
  expect(empty.generation.canSubmit.value).toBe(true)
})

it('uses the completed current version as the baseline for the next modification', async () => {
  vi.mocked(listRuns).mockResolvedValue({ items: [], nextCursor: null })
  const { generation, project, prompt } = setup('创建计数器')
  await flushPromises()
  vi.mocked(submitRun).mockImplementation(async () => {
    vi.mocked(listRuns).mockResolvedValue({
      items: [{ ...task, status: 'SUCCEEDED', resultVersionId: 'version-1' }],
      nextCursor: null,
    })
    vi.mocked(getProject).mockResolvedValue({
      ...initial,
      projectRevision: 1,
      currentVersionId: 'version-1',
    })
    return { runId: task.id, status: 'QUEUED' }
  })
  await generation.submit()
  expect(project.value.currentVersionId).toBe('version-1')
  expect(prompt.value).toBe('')
  expect(submitRun).toHaveBeenNthCalledWith(
    1,
    initial.id,
    { prompt: task.prompt, intent: 'CREATE', baseVersionId: null, expectedProjectRevision: 0 },
    expect.any(String),
  )
  prompt.value = '增加重置按钮'
  await generation.submit()
  expect(submitRun).toHaveBeenNthCalledWith(
    2,
    initial.id,
    {
      prompt: '增加重置按钮',
      intent: 'MODIFY',
      baseVersionId: 'version-1',
      expectedProjectRevision: 1,
    },
    expect.any(String),
  )
  expect(vi.mocked(submitRun).mock.calls[0]![2]).not.toBe(vi.mocked(submitRun).mock.calls[1]![2])
})

it('subscribes from the snapshot, applies live stages and refreshes the committed terminal version', async () => {
  vi.mocked(listRuns).mockResolvedValue({
    items: [{ ...task, status: 'RUNNING', stage: 'PLANNING' }],
    nextCursor: null,
  })
  const { generation, project } = setup()
  await flushPromises()
  expect(subscribeToRun).toHaveBeenCalledWith(task.id, 3, expect.any(Object))
  const handlers = vi.mocked(subscribeToRun).mock.calls[0]![2]
  handlers.state('connected')
  handlers.event({ type: 'stage.started', seq: 4, stage: 'BUILDING' })
  expect(generation.run.value?.stage).toBe('BUILDING')
  expect(generation.run.value?.lastEventSeq).toBe(4)
  vi.mocked(listRuns).mockResolvedValue({
    items: [
      { ...task, status: 'SUCCEEDED', stage: 'SUCCEEDED', resultVersionId: 'v2', lastEventSeq: 5 },
    ],
    nextCursor: null,
  })
  vi.mocked(getProject).mockResolvedValue({
    ...initial,
    currentVersionId: 'v2',
    projectRevision: 2,
  })
  handlers.event({ type: 'run.completed', seq: 5, lastEventSeq: 5 })
  await flushPromises()
  expect(project.value.currentVersionId).toBe('v2')
  expect(submitRun).not.toHaveBeenCalled()
  expect(vi.mocked(subscribeToRun).mock.results[0]!.value).toHaveBeenCalled()
})

it('closes an old project stream and ignores its late events after navigation', async () => {
  vi.mocked(listRuns).mockResolvedValue({
    items: [{ ...task, status: 'RUNNING', stage: 'PLANNING' }],
    nextCursor: null,
  })
  const { generation, project } = setup()
  await flushPromises()
  const handlers = vi.mocked(subscribeToRun).mock.calls[0]![2]
  vi.mocked(listRuns).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(getProject).mockResolvedValue({ ...initial, id: 'project-2' })
  project.value = { ...initial, id: 'project-2' }
  await flushPromises()
  handlers.event({ type: 'stage.started', seq: 4, stage: 'BUILDING' })
  handlers.error('旧任务错误')
  expect(generation.run.value).toBeNull()
  expect(generation.error.value).toBe('')
  expect(vi.mocked(subscribeToRun).mock.results[0]!.value).toHaveBeenCalled()
})
