import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useProjectDraft } from './useProjectDraft'
import { readDraft, saveDraft } from '../api/drafts'
import { ApiFailure } from '../api/client'
vi.mock('../api/drafts', () => ({ readDraft: vi.fn(), saveDraft: vi.fn() }))
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
})
afterEach(() => vi.useRealTimers())
function setup(local = false) {
  const cached = ref(local)
  const target = ref<{ id: string; ownerId: string } | null>({ id: 'p', ownerId: 'u' })
  const prompt = ref(local ? '从首页带入' : '')
  let draft!: ReturnType<typeof useProjectDraft>
  const wrapper = mount(
    defineComponent({
      setup() {
        draft = useProjectDraft(
          () => target.value,
          prompt,
          () => cached.value,
        )
        return () => null
      },
    }),
  )
  return {
    wrapper,
    target,
    cached,
    prompt,
    get draft() {
      return draft
    },
  }
}
it('restores server drafts then serializes rapid edits and revisions', async () => {
  vi.mocked(readDraft).mockResolvedValue({ prompt: '已保存', revision: 4 })
  vi.mocked(saveDraft).mockImplementation(async (_, input) => ({
    prompt: input.prompt,
    revision: input.expectedRevision + 1,
  }))
  const s = setup()
  await flushPromises()
  expect(s.prompt.value).toBe('已保存')
  s.prompt.value = '第一次'
  s.prompt.value = '最后一次'
  await vi.advanceTimersByTimeAsync(650)
  expect(saveDraft).toHaveBeenCalledTimes(1)
  expect(saveDraft).toHaveBeenLastCalledWith('p', { prompt: '最后一次', expectedRevision: 4 })
  expect(s.draft.status.value).toBe('saved')
  s.wrapper.unmount()
})
it('keeps local text after conflict and never automatically overwrites remote text', async () => {
  vi.mocked(readDraft).mockResolvedValue({ prompt: '另一页草稿', revision: 2 })
  const s = setup(true)
  await flushPromises()
  await vi.advanceTimersByTimeAsync(650)
  expect(s.prompt.value).toBe('从首页带入')
  expect(s.draft.status.value).toBe('conflict')
  expect(saveDraft).not.toHaveBeenCalled()
  s.wrapper.unmount()
})
it('retries the unknown write exactly before sending newer input', async () => {
  vi.mocked(readDraft).mockResolvedValue({ prompt: '', revision: 0 })
  vi.mocked(saveDraft)
    .mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断网'))
    .mockImplementation(async (_, input) => ({
      prompt: input.prompt,
      revision: input.expectedRevision + 1,
    }))
  const s = setup()
  await flushPromises()
  s.prompt.value = '原提交'
  await vi.advanceTimersByTimeAsync(650)
  s.prompt.value = '较新输入'
  await vi.advanceTimersByTimeAsync(650)
  expect(saveDraft).toHaveBeenCalledTimes(1)
  await s.draft.retry()
  await vi.advanceTimersByTimeAsync(650)
  expect(vi.mocked(saveDraft).mock.calls.map((call) => call[1])).toEqual([
    { prompt: '原提交', expectedRevision: 0 },
    { prompt: '原提交', expectedRevision: 0 },
    { prompt: '较新输入', expectedRevision: 1 },
  ])
  s.wrapper.unmount()
})
it('treats a saved empty draft as a remote edit rather than a pristine draft', async () => {
  vi.mocked(readDraft).mockResolvedValue({ prompt: '', revision: 2 })
  const s = setup(true)
  try {
    await flushPromises()
    await vi.advanceTimersByTimeAsync(650)
    expect(s.prompt.value).toBe('从首页带入')
    expect(s.draft.status.value).toBe('conflict')
    expect(saveDraft).not.toHaveBeenCalled()
  } finally {
    s.wrapper.unmount()
  }
})
it('preserves input typed after a conflict reload starts', async () => {
  vi.mocked(readDraft).mockResolvedValueOnce({ prompt: '其他页面', revision: 2 })
  const s = setup(true)
  try {
    await flushPromises()
    let resolve!: (value: { prompt: string; revision: number }) => void
    vi.mocked(readDraft).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const loading = s.draft.reload()
    s.prompt.value = '点击重新读取之后输入的新内容'
    resolve({ prompt: '其他页面', revision: 2 })
    await loading
    await vi.advanceTimersByTimeAsync(650)
    expect(s.prompt.value).toBe('点击重新读取之后输入的新内容')
    expect(s.draft.status.value).toBe('conflict')
    expect(saveDraft).not.toHaveBeenCalled()
  } finally {
    s.wrapper.unmount()
  }
})
it('ignores a prior account or project response', async () => {
  let resolve!: (value: { prompt: string; revision: number }) => void
  vi.mocked(readDraft).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    }),
  )
  const s = setup()
  s.target.value = null
  await flushPromises()
  resolve({ prompt: '旧身份私有内容', revision: 1 })
  await flushPromises()
  expect(s.prompt.value).toBe('')
  s.wrapper.unmount()
})
it('keeps unsaved warnings while identity is revalidated and clears them with the private buffer', async () => {
  vi.mocked(readDraft).mockResolvedValue({ prompt: '已保存', revision: 1 })
  const s = setup()
  try {
    await flushPromises()
    s.cached.value = true
    s.prompt.value = '' // Deleting all text is also an unsaved edit.
    expect(s.draft.dirty.value).toBe(true)
    s.target.value = null
    expect(s.draft.dirty.value).toBe(true)
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    await vi.advanceTimersByTimeAsync(650)
    expect(saveDraft).not.toHaveBeenCalled()
    s.cached.value = false // A confirmed identity change clears the private store.
    expect(s.draft.dirty.value).toBe(false)
  } finally {
    s.wrapper.unmount()
  }
})
