import { afterEach, expect, it, vi } from 'vitest'
import { workspaceEntry } from './workspaceEntry'
afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})
it('remembers workspace entry across router instances using only a boolean', () => {
  const first = workspaceEntry()
  expect(first.hasEntered()).toBe(false)
  first.enter()
  expect(workspaceEntry().hasEntered()).toBe(true)
  expect(localStorage.getItem('atoms.workspace.entered.v1')).toBe('1')
})
it('falls back to in-memory layout state when storage is blocked', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('blocked')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('blocked')
  })
  const entry = workspaceEntry()
  expect(entry.hasEntered()).toBe(false)
  entry.enter()
  expect(entry.hasEntered()).toBe(true)
})
