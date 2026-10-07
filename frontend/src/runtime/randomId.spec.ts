import { afterEach, expect, it, vi } from 'vitest'
import { randomId } from './randomId'

afterEach(() => vi.unstubAllGlobals())

it('creates v4 identifiers using secure random bytes when HTTP has no randomUUID', () => {
  const getRandomValues = vi.fn((bytes: Uint8Array) => bytes.fill(255))
  vi.stubGlobal('crypto', { getRandomValues })
  expect(randomId()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
  expect(getRandomValues).toHaveBeenCalledOnce()
  expect(getRandomValues.mock.calls[0]![0]).toHaveLength(16)
})

it('does not fall back to weak randomness if the cryptographic source fails', () => {
  vi.stubGlobal('crypto', {
    getRandomValues: () => {
      throw new Error('unavailable')
    },
  })
  expect(() => randomId()).toThrow('unavailable')
})
