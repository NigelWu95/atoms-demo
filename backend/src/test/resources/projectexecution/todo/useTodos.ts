import { onMounted, onUnmounted, ref } from 'vue'

export type Todo = { id: number; title: string; completed: boolean; revision: number; priority?: number }
export function validTitle(value: string) {
  const title = value.trim()
  return title.length > 0 && [...title].length <= 160 && !/[\u0000-\u001f\u007f-\u009f]/u.test(title)
}
class ApiFailure extends Error { constructor(readonly status: number, readonly timedOut = false) { super('Todo request failed') } }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiFailure(0)
  return value as Record<string, unknown>
}
function positive(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 }

export function useTodos(withPriority: boolean) {
  const items = ref<Todo[]>([]), nextAfter = ref<number | null>(null)
  const ready = ref(false), busy = ref(false), needsReload = ref(false)
  const error = ref(''), notice = ref('正在加载待办…')
  let disposed = false, active: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined

  function parseTodo(value: unknown): Todo {
    const row = record(value)
    if (!positive(row.id) || !positive(row.revision) || typeof row.title !== 'string' || !validTitle(row.title)
      || typeof row.completed !== 'boolean' || withPriority && (!Number.isInteger(row.priority) || Number(row.priority) < 0 || Number(row.priority) > 2)) throw new ApiFailure(0)
    return { id: row.id, title: row.title, completed: row.completed, revision: row.revision, ...(withPriority ? { priority: Number(row.priority) } : {}) }
  }
  async function request(path: string, method = 'GET', body?: object): Promise<unknown> {
    const controller = new AbortController(); active = controller
    let timedOut = false
    timer = setTimeout(() => { timedOut = true; controller.abort() }, 10_000)
    try {
      const result = await fetch(`./api/todos${path}`, { method, credentials: 'omit', redirect: 'error', cache: 'no-store', signal: controller.signal,
        ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) })
      if (result.status !== (method === 'POST' ? 201 : 200)) throw new ApiFailure(result.status)
      const value: unknown = await result.json()
      if (controller.signal.aborted) throw new ApiFailure(0, timedOut)
      return value
    } catch (failure) {
      if (timedOut) throw new ApiFailure(0, true)
      throw failure
    } finally { clearTimeout(timer); timer = undefined; active = undefined }
  }
  async function perform(write: boolean, action: () => Promise<void>): Promise<boolean> {
    if (disposed || busy.value || write && (!ready.value || needsReload.value)) return false
    busy.value = true; error.value = ''; notice.value = write ? '正在保存…' : '正在加载…'
    try { await action(); return !disposed }
    catch (failure) {
      if (!disposed) {
        notice.value = ''
        const status = failure instanceof ApiFailure ? failure.status : 0
        if (write) {
          needsReload.value = status !== 400
          error.value = status === 409 ? '这条待办已在别处修改。输入已保留，请复制需要保留的内容后刷新。'
            : status === 404 ? '这条待办已不存在，请刷新列表。'
            : status === 400 ? '内容未保存，请检查标题和优先级后再试。'
            : '无法确认本次操作是否已保存。输入已保留，请先刷新并核对列表，避免重复提交。'
        } else error.value = failure instanceof ApiFailure && failure.timedOut ? '加载超时，请重试。' : '暂时无法加载待办，请重试。'
      }
      return false
    } finally { if (!disposed) busy.value = false }
  }
  function merge(rows: Todo[]) {
    const combined = new Map(items.value.map(row => [row.id, row]))
    for (const row of rows) if ((combined.get(row.id)?.revision ?? 0) <= row.revision) combined.set(row.id, row)
    items.value = [...combined.values()].sort((a, b) => a.id - b.id)
  }
  async function load(append = false) {
    if (append && nextAfter.value === null) return false
    return perform(false, async () => {
      const after = append ? nextAfter.value! : 0
      const body = record(await request(`?limit=100&after=${after}`))
      if (!Array.isArray(body.items) || body.items.length > 100) throw new ApiFailure(0)
      const rows = body.items.map(parseTodo)
      let last = after
      for (const row of rows) { if (row.id <= last) throw new ApiFailure(0); last = row.id }
      if (body.nextAfter !== null && (!positive(body.nextAfter) || !rows.length || body.nextAfter !== last)) throw new ApiFailure(0)
      if (!disposed) {
        if (append) merge(rows); else items.value = rows
        nextAfter.value = body.nextAfter; ready.value = true
        notice.value = needsReload.value ? '已重新加载，请核对原操作是否已保存。' : '列表已更新。'
        needsReload.value = false
      }
    })
  }
  function priority(value: number) {
    if (withPriority && (!Number.isInteger(value) || value < 0 || value > 2)) throw new ApiFailure(400)
    return withPriority ? { priority: value } : {}
  }
  async function create(title: string, level = 0) {
    if (!validTitle(title)) return false
    return perform(true, async () => {
      const row = parseTodo(await request('', 'POST', { title: title.trim(), ...priority(level) }))
      if (items.value.some(item => item.id === row.id)) throw new ApiFailure(0)
      if (!disposed) { merge([row]); notice.value = '已添加待办。' }
    })
  }
  async function update(original: Todo, title: string, completed: boolean, level = original.priority ?? 0) {
    if (!validTitle(title)) return false
    return perform(true, async () => {
      const row = parseTodo(await request(`/${original.id}`, 'PUT', { title: title.trim(), completed, revision: original.revision, ...priority(level) }))
      if (row.id !== original.id || row.revision <= original.revision) throw new ApiFailure(0)
      if (!disposed) { merge([row]); notice.value = '修改已保存。' }
    })
  }
  onMounted(() => { void load() })
  onUnmounted(() => { disposed = true; clearTimeout(timer); active?.abort() })
  return { items, nextAfter, ready, busy, needsReload, error, notice, load, create, update }
}
