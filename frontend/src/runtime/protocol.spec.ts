import { expect, it } from 'vitest'
import { jsonObject } from './protocol'

it('accepts JSON within the backend size limit, including many small nodes', () => {
  expect(jsonObject({ items: Array(10001).fill(0) })).toBe(true)
  let deep: Record<string, unknown> = {}
  for (let i = 0; i < 70; i++) deep = { child: deep }
  expect(jsonObject(deep)).toBe(true)
})
