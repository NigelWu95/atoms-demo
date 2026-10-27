import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { useSourceBrowser } from './useSourceBrowser'

const { read } = vi.hoisted(() => ({ read: vi.fn() }))
vi.mock('../api/projectWorkbench', async (original) => ({
  ...(await original<typeof import('../api/projectWorkbench')>()),
  read,
}))
afterEach(() => vi.resetAllMocks())

function deferred() {
  let resolve!: (value: unknown) => void
  const promise = new Promise((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function setup() {
  const target = ref<{ project: string; owner: string; version: string } | null>(null)
  let source!: ReturnType<typeof useSourceBrowser>
  const wrapper = mount(
    defineComponent({
      setup() {
        source = useSourceBrowser(() => target.value)
        return () => null
      },
    }),
  )
  return { target, source, wrapper }
}
const entry = (path: string) => ({ path, mode: 33188, bytes: 7, sha256: 'a'.repeat(64) })
const file = (path: string) => ({ ...entry(path), content: path, truncated: false })

it('clears private source on account change and ignores a late file response', async () => {
  const pending = deferred()
  read.mockResolvedValueOnce([entry('README.md')]).mockReturnValueOnce(pending.promise)
  const state = setup()
  state.target.value = { project: 'A', owner: 'first', version: 'v1' }
  await flushPromises()
  expect(state.source.tree.value).toHaveLength(1)
  const signal = read.mock.calls[1]![3] as AbortSignal
  state.target.value = null
  expect(state.source.tree.value).toEqual([])
  expect(signal.aborted).toBe(true)
  pending.resolve(file('README.md'))
  await flushPromises()
  expect(state.source.file.value).toBeNull()
  expect(state.source.error.value).toBe('')
  state.wrapper.unmount()
})

it('shows the latest chosen file when earlier requests complete out of order', async () => {
  const first = deferred(),
    second = deferred()
  read
    .mockResolvedValueOnce([entry('README.md'), entry('main.ts')])
    .mockReturnValueOnce(first.promise)
    .mockReturnValueOnce(second.promise)
  const state = setup()
  state.target.value = { project: 'A', owner: 'owner', version: 'v1' }
  await flushPromises()
  const selecting = state.source.selectFile('main.ts')
  second.resolve(file('main.ts'))
  await selecting
  first.resolve(file('README.md'))
  await flushPromises()
  expect(state.source.selectedPath.value).toBe('main.ts')
  expect(state.source.file.value?.content).toBe('main.ts')
  state.wrapper.unmount()
})
