import { expect, it, vi } from 'vitest'
import { useWorkbenchIntent } from './useWorkbenchIntent'
import { ApiFailure } from '../api/client'
it('keeps the exact key after uncertainty and requires explicit retry', async () => {
  const intent = useWorkbenchIntent(),
    execute = vi
      .fn()
      .mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '断网'))
      .mockResolvedValue(undefined)
  await intent.run('构建', execute)
  expect(intent.pending.value).toBe('构建')
  const other = vi.fn()
  await intent.run('另一个任务', other)
  expect(other).not.toHaveBeenCalled()
  await intent.retry()
  expect(execute).toHaveBeenCalledTimes(2)
  expect(execute.mock.calls[0]![0]).toBe(execute.mock.calls[1]![0])
  expect(intent.pending.value).toBeNull()
})
it('a late result after switching scope cannot clear a new intent', async () => {
  const intent = useWorkbenchIntent()
  let finish!: () => void
  const old = intent.run(
    '旧操作',
    () =>
      new Promise<void>((resolve) => {
        finish = resolve
      }),
  )
  intent.clear()
  await intent.run('新操作', async () => {
    throw new ApiFailure('NETWORK_ERROR', '待确认')
  })
  finish()
  await old
  expect(intent.pending.value).toBe('新操作')
  expect(intent.error.value).toBe('待确认')
})
it('validation failure releases the intent and preserves a useful error', async () => {
  const intent = useWorkbenchIntent()
  await intent.run('需求', async () => {
    throw new ApiFailure('INVALID', '需要源码版本', 422)
  })
  expect(intent.pending.value).toBeNull()
  expect(intent.error.value).toBe('需要源码版本')
})
