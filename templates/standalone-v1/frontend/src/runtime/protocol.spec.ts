import { describe, expect, it } from 'vitest'
import { jsonObject, parseRequest, PROTOCOL } from './protocol'

const channelId = 'e1de3855-413f-4884-8e77-16e622511233'
const requestId = 'a9319861-144e-46ea-aed6-c081e763b1d8'
const request = (type: string, payload: unknown = {}) => ({
  protocol: PROTOCOL,
  channelId,
  requestId,
  type,
  payload,
})

describe('standalone SDK protocol', () => {
  it('accepts only exact envelopes on the current channel', () => {
    const get = request('STATE_GET')
    expect(parseRequest(get, channelId)).toEqual({ type: 'STATE_GET', payload: {}, requestId })
    for (const invalid of [
      null,
      [],
      { ...get, channelId: 'old' },
      { ...get, protocol: 'v2' },
      { ...get, requestId: 'invalid' },
      { ...get, requestId: `${requestId}\n` },
      { ...get, projectId: 'other' },
      request('FETCH'),
    ]) {
      expect(parseRequest(invalid, channelId)).toBeNull()
    }
  })

  it('rejects extra authority and invalid revision numbers without invoking storage', () => {
    const save = { value: { count: 1 }, expectedRevision: 0 }
    expect(parseRequest(request('STATE_SAVE', save), channelId)).toMatchObject({
      type: 'STATE_SAVE',
      payload: save,
    })
    for (const payload of [
      { ...save, userId: 'other' },
      { ...save, expectedRevision: -1 },
      { ...save, expectedRevision: 0.5 },
      { ...save, expectedRevision: Number.MAX_SAFE_INTEGER },
      { ...save, value: [] },
    ]) {
      expect(parseRequest(request('STATE_SAVE', payload), channelId)?.type).toBe('INVALID_INPUT')
    }
    expect(parseRequest(request('STATE_GET', { path: '/private' }), channelId)?.type).toBe(
      'INVALID_INPUT',
    )
  })

  it('bounds JSON by UTF-8 bytes and container depth including the HTTP wrapper', () => {
    expect(jsonObject({ s: 'a'.repeat(256 * 1024 - 8) })).toBe(true)
    expect(jsonObject({ s: 'a'.repeat(256 * 1024 - 7) })).toBe(false)
    expect(jsonObject({ s: '界'.repeat(90000) })).toBe(false)
    let nested: Record<string, unknown> = {}
    for (let i = 1; i < 63; i++) nested = { nested }
    expect(jsonObject(nested)).toBe(true)
    expect(jsonObject({ nested })).toBe(false)
    const cyclic: Record<string, unknown> = {}
    cyclic.self = cyclic
    for (const invalid of [
      cyclic,
      new Date(),
      { n: Infinity },
      { n: NaN },
      { n: 1n },
      { n: undefined },
      { n: () => 1 },
    ])
      expect(jsonObject(invalid)).toBe(false)
  })

  it('accepts bounded runtime events, not arbitrary message properties', () => {
    expect(parseRequest(request('RUNTIME_READY'), channelId)?.type).toBe('RUNTIME_READY')
    expect(
      parseRequest(request('RUNTIME_ERROR', { message: 'x'.repeat(1000) }), channelId)?.type,
    ).toBe('RUNTIME_ERROR')
    for (const invalid of [
      request('RUNTIME_READY', { token: 'private' }),
      request('RUNTIME_ERROR', { message: 'x'.repeat(1001) }),
      request('RUNTIME_ERROR', { message: 1 }),
    ])
      expect(parseRequest(invalid, channelId)).toBeNull()
  })
})
