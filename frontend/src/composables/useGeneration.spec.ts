import { createPinia, disposePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getProject, type Project } from '../api/projects'
import { generationAvailability, listMessages, listRuns, submitRun, type Run } from '../api/runs'
import { useGeneration } from './useGeneration'
import { subscribeToRun } from '../api/runEvents'
import { useSessionStore } from '../stores/session'
import { useGenerationStore } from '../stores/generation'
import { ApiFailure } from '../api/client'
import { repairPrompt, type RepairProposal } from '../runtime/previewRepair'

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
  id: '22222222-2222-4222-8222-222222222222',
  name: 'App',
  ownerId: '11111111-1111-4111-8111-111111111111',
  projectRevision: 0,
  currentVersionId: null,
  createdAt: '2026-10-07T00:00:00Z',
  updatedAt: '2026-10-07T00:00:00Z',
}
const task: Run = {
  id: '44444444-4444-4444-8444-444444444444',
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
const stores = new Set<ReturnType<typeof createPinia>>()
function setup(draft = '', pinia = createPinia()) {
  stores.add(pinia)
  const project = ref(initial)
  const prompt = ref(draft)
  let generation!: ReturnType<typeof useGeneration>
  const session = useSessionStore(pinia)
  session.state = 'ready'
  session.identity = { userId: initial.ownerId, displayName: '测试', isGuest: true }
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
      { global: { plugins: [pinia] } },
    ),
  )
  return {
    generation,
    project,
    prompt,
    session,
    pinia,
    intents: useGenerationStore(pinia),
    unmount: () => wrappers.pop()!.unmount(),
  }
}
beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
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
  stores.forEach(disposePinia)
  stores.clear()
  vi.restoreAllMocks()
})

it('restores a normal pending request unchanged and discards it only after explicit confirmation', async () => {
  vi.mocked(listRuns).mockResolvedValue({ items: [], nextCursor: null })
  const first = setup('原始需求')
  await flushPromises()
  vi.mocked(submitRun).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果未知'))
  await first.generation.submit()
  const original = first.generation.pending.value
  first.unmount()
  first.intents.$dispose()
  const second = setup('新的草稿')
  await flushPromises()
  expect(second.generation.pending.value).toEqual(original)
  expect(second.generation.canSubmit.value).toBe(false)
  expect(submitRun).toHaveBeenCalledTimes(1)
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  second.generation.discardRecovery()
  expect(second.generation.pending.value).toEqual(original)
  confirm.mockReturnValue(true)
  second.generation.discardRecovery()
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('不会取消后台任务'))
  expect(second.generation.pending.value).toBeUndefined()
  expect(second.generation.canSubmit.value).toBe(true)
  expect(second.prompt.value).toBe('新的草稿')
  expect(submitRun).toHaveBeenCalledTimes(1)
})

it('ignores an accepted response after the same account starts a new recovery epoch', async () => {
  const { generation, intents, prompt } = await setupRepair()
  let finish!: (value: { runId: string }) => void
  vi.mocked(submitRun).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const old = generation.submit()
  localStorage.setItem('atoms.session.changed', 'new-login')
  intents.bind({ userId: initial.ownerId, isGuest: true })
  finish({ runId: task.id })
  await old
  expect(prompt.value).toBe('尚未提交的新需求')
  expect(intents.pending).toEqual({})
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

const repairProject = {
  ...initial,
  currentVersionId: '33333333-3333-4333-8333-333333333333',
  projectRevision: 1,
}
const proposal: RepairProposal = {
  projectId: initial.id,
  ownerId: initial.ownerId,
  baseVersionId: repairProject.currentVersionId,
  expectedProjectRevision: 1,
  versionNo: 1,
  diagnosis: 'SYNTAX',
}
async function setupRepair(draft = '尚未提交的新需求') {
  vi.mocked(getProject).mockResolvedValue({ ...repairProject })
  vi.mocked(listRuns).mockResolvedValue({ items: [], nextCursor: null })
  const result = setup(draft)
  await flushPromises()
  return result
}
it.each(['', '尚未提交的新需求', repairPrompt(proposal, '')])(
  'submits a bound repair without replacing draft %j, even on failure',
  async (draft) => {
    const { generation, prompt } = await setupRepair(draft)
    vi.mocked(submitRun).mockImplementation(async () => {
      vi.mocked(listRuns).mockResolvedValue({
        items: [{ ...task, prompt: repairPrompt(proposal, '') }],
        nextCursor: null,
      })
      return { runId: task.id }
    })
    expect(await generation.submitRepair(proposal, '')).toBe(true)
    expect(submitRun).toHaveBeenCalledWith(
      initial.id,
      {
        prompt: repairPrompt(proposal, ''),
        intent: 'MODIFY',
        baseVersionId: repairProject.currentVersionId,
        expectedProjectRevision: 1,
      },
      expect.any(String),
    )
    expect(prompt.value).toBe(draft)
    await generation.refresh()
    expect(prompt.value).toBe(draft)
  },
)
it('retries an unknown repair with the original body/key and still preserves the user draft', async () => {
  const { generation, prompt } = await setupRepair()
  vi.mocked(submitRun).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果未知'))
  await generation.submitRepair(proposal, '补充说明')
  expect(generation.pending.value).toBeTruthy()
  expect(await generation.submitRepair(proposal, '不能覆盖原请求')).toBe(false)
  prompt.value = '之后继续输入的草稿'
  vi.mocked(submitRun).mockResolvedValue({ runId: task.id })
  await generation.submit()
  expect(vi.mocked(submitRun).mock.calls[0]).toEqual(vi.mocked(submitRun).mock.calls[1])
  expect(prompt.value).toBe('之后继续输入的草稿')
})
it.each(['version', 'revision', 'owner', 'session', 'active', 'length'] as const)(
  'rejects repair on invalid %s without requesting the model',
  async (change) => {
    const { generation, project, session } = await setupRepair()
    if (change === 'version') project.value.currentVersionId = 'new-version'
    if (change === 'revision') project.value.projectRevision++
    if (change === 'owner')
      session.identity = { userId: 'another', displayName: 'other', isGuest: true }
    if (change === 'session') session.state = 'connecting'
    if (change === 'active') generation.run.value = { ...task, status: 'RUNNING' }
    expect(
      await generation.submitRepair(proposal, change === 'length' ? 'x'.repeat(1001) : ''),
    ).toBe(false)
    expect(submitRun).not.toHaveBeenCalled()
  },
)

it('does not start a repair while the latest project and run are still refreshing', async () => {
  const { generation } = await setupRepair()
  let finish!: (value: { items: Run[]; nextCursor: null }) => void
  vi.mocked(listRuns).mockReturnValueOnce(new Promise((resolve) => (finish = resolve)))
  const refresh = generation.refresh()
  try {
    expect(generation.loading.value).toBe(true)
    expect(await generation.submitRepair(proposal, '')).toBe(false)
    expect(submitRun).not.toHaveBeenCalled()
  } finally {
    finish({ items: [], nextCursor: null })
    await refresh
  }
})

it('submits only once when repair confirmation is clicked again before the response', async () => {
  const { generation } = await setupRepair()
  let finish!: (value: { runId: string }) => void
  vi.mocked(submitRun).mockReturnValueOnce(new Promise((resolve) => (finish = resolve)))
  const first = generation.submitRepair(proposal, '')
  expect(await generation.submitRepair(proposal, '')).toBe(false)
  expect(submitRun).toHaveBeenCalledTimes(1)
  finish({ runId: task.id })
  await first
})

it.each(['success', 'rejection'] as const)(
  'ignores a late repair %s after the account changes, preserving the new pending request',
  async (outcome) => {
    const { generation, session, intents, prompt } = await setupRepair()
    let finish!: (value: { runId: string }) => void
    let reject!: (failure: unknown) => void
    vi.mocked(submitRun).mockReturnValueOnce(
      new Promise((resolve, fail) => {
        finish = resolve
        reject = fail
      }),
    )
    const oldRequest = generation.submitRepair(proposal, '')
    session.invalidate('账号切换')
    session.identity = { userId: 'new-owner', displayName: '新账号', isGuest: false }
    session.state = 'ready'
    const next = intents.intent(initial.id, {
      prompt: '新账号请求',
      intent: 'MODIFY',
      baseVersionId: 'new-version',
      expectedProjectRevision: 2,
    })
    prompt.value = '新账号草稿'
    generation.error.value = '新账号界面状态'
    const reads = vi.mocked(getProject).mock.calls.length
    if (outcome === 'success') finish({ runId: task.id })
    else reject(new ApiFailure('STALE_VERSION', '旧请求失败', 409))
    await oldRequest
    expect(intents.pending[initial.id]).toEqual(next)
    expect(prompt.value).toBe('新账号草稿')
    expect(generation.error.value).toBe('新账号界面状态')
    expect(generation.run.value).toBeNull()
    expect(getProject).toHaveBeenCalledTimes(reads)
  },
)

it('preserves repair retry metadata and an empty draft across route remounts', async () => {
  const first = await setupRepair('')
  vi.mocked(submitRun).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果未知'))
  await first.generation.submitRepair(proposal, '')
  first.unmount()
  vi.mocked(listRuns).mockResolvedValue({
    items: [{ ...task, prompt: repairPrompt(proposal, '') }],
    nextCursor: null,
  })
  const second = setup('', first.pinia)
  await flushPromises()
  expect(second.prompt.value).toBe('')
  expect(second.generation.pending.value?.preserveDraft).toBe(true)
  vi.mocked(submitRun).mockResolvedValue({ runId: task.id })
  await second.generation.submit()
  expect(vi.mocked(submitRun).mock.calls[1]).toEqual(vi.mocked(submitRun).mock.calls[0])
  expect(second.prompt.value).toBe('')
  second.unmount()
  const third = setup('', first.pinia)
  await flushPromises()
  expect(third.generation.pending.value).toBeUndefined()
  expect(third.prompt.value).toBe('')
})

it('restores an unknown repair across a fresh application without submitting until clicked', async () => {
  const first = await setupRepair('')
  vi.mocked(submitRun).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果未知'))
  await first.generation.submitRepair(proposal, '必须保留原内容')
  first.unmount()
  first.intents.$dispose()
  vi.mocked(listRuns).mockResolvedValue({
    items: [{ ...task, prompt: repairPrompt(proposal, '必须保留原内容') }],
    nextCursor: null,
  })
  const second = setup('')
  await flushPromises()
  expect(submitRun).toHaveBeenCalledTimes(1)
  expect(second.prompt.value).toBe('')
  expect(second.generation.pending.value?.preserveDraft).toBe(true)
  expect(second.generation.canSubmit.value).toBe(false)
  second.prompt.value = '刷新后写的新草稿'
  vi.mocked(submitRun).mockResolvedValue({ runId: task.id })
  await second.generation.submit()
  expect(vi.mocked(submitRun).mock.calls[1]).toEqual(vi.mocked(submitRun).mock.calls[0])
  expect(second.prompt.value).toBe('刷新后写的新草稿')
  second.unmount()
  second.intents.$dispose()
  const third = setup('')
  await flushPromises()
  expect(third.prompt.value).toBe('')
  expect(third.generation.pending.value).toBeUndefined()
  expect(submitRun).toHaveBeenCalledTimes(2)
})

it('does not restore or submit a request before the latest project ownership is verified', async () => {
  const first = await setupRepair()
  vi.mocked(submitRun).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果未知'))
  await first.generation.submitRepair(proposal, '')
  first.unmount()
  first.intents.$dispose()
  vi.mocked(getProject).mockResolvedValue({ ...repairProject, ownerId: 'another-owner' })
  const second = setup()
  await flushPromises()
  expect(second.generation.pending.value).toBeUndefined()
  expect(second.generation.canSubmit.value).toBe(false)
  await second.generation.submit()
  expect(submitRun).toHaveBeenCalledTimes(1)
})
