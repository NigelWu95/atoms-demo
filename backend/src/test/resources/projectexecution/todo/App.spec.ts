import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App.vue'

const withPriority = false
const todo = (id = 1, title = '整理演示材料', revision = 1) => ({ id, title, completed: false, revision, ...(withPriority ? { priority: 0 } : {}) })
const response = (body: unknown, status = 200) => ({ status, json: async () => body })
const page = (items: unknown[] = [], nextAfter: number | null = null) => response({ items, nextAfter })
const mounted: VueWrapper[] = []
function render() { const wrapper = mount(App); mounted.push(wrapper); return wrapper }
afterEach(() => { mounted.forEach(w => w.unmount()); mounted.length = 0; vi.useRealTimers() })

it('loads its own business API and shows the empty state', async () => {
  const request = vi.fn().mockResolvedValue(page()); vi.stubGlobal('fetch', request)
  const w = render()
  expect(w.get('[data-test="add"]').attributes('disabled')).toBeDefined()
  await flushPromises()
  expect(request).toHaveBeenCalledWith('./api/todos?limit=100&after=0', expect.objectContaining({ method: 'GET', credentials: 'omit', redirect: 'error', signal: expect.any(AbortSignal) }))
  expect(w.text()).toContain('还没有待办')
  expect(w.find('iframe').exists()).toBe(false)
  expect(w.find('#new-priority').exists()).toBe(withPriority)
})

it('creates once, trims the title, and keeps input until acknowledged', async () => {
  let acknowledge!: (value: unknown) => void
  const request = vi.fn().mockResolvedValueOnce(page()).mockImplementationOnce(() => new Promise(resolve => { acknowledge = resolve }))
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  await w.get('[data-test="filter-done"]').trigger('click')
  await w.get('#new-title').setValue('  整理演示材料  ')
  if (withPriority) await w.get('#new-priority').setValue('2')
  await w.get('[data-test="create-form"]').trigger('submit'); await w.get('[data-test="create-form"]').trigger('submit')
  expect(request).toHaveBeenCalledTimes(2)
  expect((w.get('#new-title').element as HTMLInputElement).value).toContain('整理演示材料')
  expect(JSON.parse(request.mock.calls[1]![1].body)).toEqual({ title: '整理演示材料', ...(withPriority ? { priority: 2 } : {}) })
  acknowledge(response({ ...todo(), ...(withPriority ? { priority: 2 } : {}) }, 201)); await flushPromises()
  expect(w.findAll('[data-test="todo"]')).toHaveLength(1)
  expect((w.get('#new-title').element as HTMLInputElement).value).toBe('')
  expect(w.get('[role="status"]').text()).toContain('已添加')
})

it('edits with the original revision and updates only after success', async () => {
  const request = vi.fn().mockResolvedValueOnce(page([todo()])).mockResolvedValueOnce(response({ ...todo(1, '修改后的待办', 2), ...(withPriority ? { priority: 1 } : {}) }))
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  await w.get('[data-test="edit"]').trigger('click'); await w.get('#edit-title').setValue('修改后的待办')
  if (withPriority) await w.get('#edit-priority').setValue('1')
  await w.get('[data-test="edit-form"]').trigger('submit'); await flushPromises()
  expect(request.mock.calls[1]![0]).toBe('./api/todos/1')
  expect(JSON.parse(request.mock.calls[1]![1].body)).toEqual({ title: '修改后的待办', completed: false, revision: 1, ...(withPriority ? { priority: 1 } : {}) })
  expect(w.text()).toContain('修改后的待办'); expect(w.find('#edit-title').exists()).toBe(false)
})

it('retains a conflicting edit until the explicit discard-and-refresh action', async () => {
  const request = vi.fn().mockResolvedValueOnce(page([todo()])).mockResolvedValueOnce(response({ message: 'PRIVATE_ERROR_CANARY' }, 409)).mockResolvedValueOnce(page([todo(1, '其他页面保存的内容', 2)]))
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  await w.get('[data-test="edit"]').trigger('click'); await w.get('#edit-title').setValue('尚未保存的输入')
  await w.get('[data-test="edit-form"]').trigger('submit'); await flushPromises()
  expect(w.get('[role="alert"]').text()).toContain('已在别处修改')
  expect((w.get('#edit-title').element as HTMLInputElement).value).toBe('尚未保存的输入')
  expect(w.get('[data-test="save"]').attributes('disabled')).toBeDefined()
  expect(w.get('[data-test="refresh"]').text()).toContain('放弃编辑')
  expect(w.html()).not.toContain('PRIVATE_ERROR_CANARY')
  await w.get('[data-test="refresh"]').trigger('click'); await flushPromises()
  expect(w.text()).toContain('其他页面保存的内容'); expect(w.find('#edit-title').exists()).toBe(false)
})

it('does not replay an uncertain create and preserves its draft across refresh', async () => {
  const request = vi.fn().mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error('PRIVATE_NETWORK_CANARY')).mockResolvedValueOnce(page([todo()]))
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  await w.get('#new-title').setValue('整理演示材料'); await w.get('[data-test="create-form"]').trigger('submit'); await flushPromises()
  expect(w.get('[role="alert"]').text()).toContain('无法确认')
  await w.get('[data-test="create-form"]').trigger('submit'); expect(request).toHaveBeenCalledTimes(2)
  await w.get('[data-test="refresh"]').trigger('click'); await flushPromises()
  expect(w.findAll('[data-test="todo"]')).toHaveLength(1)
  expect((w.get('#new-title').element as HTMLInputElement).value).toBe('整理演示材料')
  expect(w.text()).not.toContain('PRIVATE_NETWORK_CANARY')
})

it('toggles completion only when confirmed, and filters loaded rows', async () => {
  const request = vi.fn().mockResolvedValueOnce(page([todo()])).mockResolvedValueOnce(response({ ...todo(1, '整理演示材料', 2), completed: true }))
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  await w.get('[data-test="toggle"]').trigger('click'); await flushPromises()
  expect(w.get('[data-test="toggle"]').attributes('aria-checked')).toBe('true')
  expect(JSON.parse(request.mock.calls[1]![1].body).completed).toBe(true)
  await w.get('[data-test="filter-open"]').trigger('click')
  expect(w.findAll('[data-test="todo"]')).toHaveLength(0)
  expect(w.text()).toContain('当前筛选下没有待办')
})

it('reads later pages and deduplicates an item created before it reaches that page', async () => {
  const request = vi.fn().mockResolvedValueOnce(page([todo(1)], 1)).mockResolvedValueOnce(response(todo(3, '新待办'), 201)).mockResolvedValueOnce(page([todo(2, '第二条'), todo(3, '新待办')]))
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  await w.get('#new-title').setValue('新待办'); await w.get('[data-test="create-form"]').trigger('submit'); await flushPromises()
  await w.get('[data-test="more"]').trigger('click'); await flushPromises()
  expect(request.mock.calls[2]![0]).toBe('./api/todos?limit=100&after=1')
  expect(w.findAll('[data-test="todo"]')).toHaveLength(3)
  expect(w.find('[data-test="more"]').exists()).toBe(false)
})

it.each([null, { items: [todo(), todo()], nextAfter: null }, { items: [{ ...todo(), title: 7 }], nextAfter: null }, { items: [], nextAfter: 1 }])('rejects malformed list data and permits retry', async body => {
  const request = vi.fn().mockResolvedValueOnce(response(body)).mockResolvedValueOnce(page())
  vi.stubGlobal('fetch', request); const w = render(); await flushPromises()
  expect(w.find('[role="alert"]').exists()).toBe(true)
  await w.get('[data-test="refresh"]').trigger('click'); await flushPromises()
  expect(w.find('[role="alert"]').exists()).toBe(false)
})

it('matches the backend Unicode title limit', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(page())); const w = render(); await flushPromises()
  await w.get('#new-title').setValue('😀'.repeat(160)); expect(w.get('[data-test="add"]').attributes('disabled')).toBeUndefined()
  await w.get('#new-title').setValue('😀'.repeat(161)); expect(w.get('[data-test="add"]').attributes('disabled')).toBeDefined()
  await w.get('#new-title').setValue('   '); expect(w.get('[data-test="add"]').attributes('disabled')).toBeDefined()
})

it('aborts a stalled read with a visible retry state', async () => {
  vi.useFakeTimers(); let signal: AbortSignal | undefined
  vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
    signal = init.signal ?? undefined; signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  })))
  const w = render(); await vi.advanceTimersByTimeAsync(10_000)
  expect(signal?.aborted).toBe(true); expect(w.get('[role="alert"]').text()).toContain('超时')
  expect(w.get('[data-test="refresh"]').attributes('disabled')).toBeUndefined()
})

it('clears its timer and aborts requests when leaving the page', async () => {
  vi.useFakeTimers(); let signal: AbortSignal | undefined
  vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
    signal = init.signal ?? undefined; signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  })))
  const w = render(); w.unmount(); mounted.pop(); await flushPromises()
  expect(signal?.aborted).toBe(true); expect(vi.getTimerCount()).toBe(0)
})
