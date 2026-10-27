import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import EngineeringBuildRepair from './EngineeringBuildRepair.vue'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'
import type { BuildRepairContext } from '../runtime/buildRepair'

const { read } = vi.hoisted(() => ({ read: vi.fn() }))
vi.mock('../api/projectWorkbench', async (original) => ({
  ...(await original<typeof import('../api/projectWorkbench')>()),
  read,
}))
afterEach(() => vi.resetAllMocks())
const context: BuildRepairContext = {
  projectId: 'project',
  ownerId: 'owner',
  head: { projectId: 'project', sourceVersionId: 'v2', revision: 2, status: 'READY' },
}
const build: ExecutionTask = {
  id: 'build',
  kind: 'BUILD',
  sourceVersionId: 'v2',
  status: 'FAILED',
  buildId: null,
  resultVersionId: null,
  errorCode: 'EXECUTION_CHECK_FAILED',
  executionPending: false,
  finishedAt: '2026-10-27T00:00:00Z',
  cancelRequested: false,
  stopRequested: false,
}
const evidence = {
  items: [
    {
      sequence: 1,
      description: 'private raw error',
      execution: { operation: 'TYPECHECK', phase: 'failed' },
    },
  ],
  hasMore: false,
  nextCursor: null,
}
const button = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll('button').find((b) => b.text() === text)!

it('prepares a read-only suggestion, cancels it, and submits only after explicit confirmation', async () => {
  read.mockResolvedValue(evidence)
  const wrapper = mount(EngineeringBuildRepair, { props: { context, build, disabled: false } })
  await button(wrapper, '准备修复需求').trigger('click')
  await flushPromises()
  expect(read).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('submit')).toBeUndefined()
  expect(wrapper.get('pre').text()).toContain('前端类型检查')
  expect(wrapper.get('pre').text()).not.toContain('private')
  await button(wrapper, '取消建议').trigger('click')
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(wrapper.emitted('submit')).toBeUndefined()
  await button(wrapper, '准备修复需求').trigger('click')
  await flushPromises()
  await wrapper.get('textarea').setValue('请保留原有功能')
  await button(wrapper, '确认提交修复').trigger('click')
  expect(wrapper.emitted('submit')).toHaveLength(1)
  expect(wrapper.emitted('submit')![0]![1]).toBe('请保留原有功能')
  wrapper.unmount()
})
it('invalidates the suggestion when its exact source revision changes', async () => {
  read.mockResolvedValue(evidence)
  const wrapper = mount(EngineeringBuildRepair, { props: { context, build, disabled: false } })
  await button(wrapper, '准备修复需求').trigger('click')
  await flushPromises()
  await wrapper.setProps({
    context: { ...context, head: { ...context.head, revision: 3, sourceVersionId: 'v3' } },
  })
  expect(wrapper.text()).toContain('历史源码')
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(wrapper.emitted('submit')).toBeUndefined()
  wrapper.unmount()
})
it('aborts reads and discards a late response after changing accounts', async () => {
  let finish!: (value: unknown) => void
  read.mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const wrapper = mount(EngineeringBuildRepair, { props: { context, build, disabled: false } })
  await button(wrapper, '准备修复需求').trigger('click')
  const signal = read.mock.calls[0]![3] as AbortSignal
  await wrapper.setProps({ context: null })
  expect(signal.aborted).toBe(true)
  finish(evidence)
  await flushPromises()
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(wrapper.emitted('submit')).toBeUndefined()
  wrapper.unmount()
})
