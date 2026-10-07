import { isRecord } from '../api/client'

export const PROTOCOL = 'atoms-runtime-v1'
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export type StateDocument = {
  value: Record<string, unknown>
  revision: number
  stateSchemaVersion: 1
}
export type StateRequest =
  | { type: 'STATE_GET'; payload: Record<string, never> }
  | { type: 'STATE_SAVE'; payload: { value: Record<string, unknown>; expectedRevision: number } }
export type RuntimeRequest = (
  | StateRequest
  | { type: 'RUNTIME_READY'; payload: Record<string, never> }
  | { type: 'RUNTIME_ERROR'; payload: { message: string } }
  | { type: 'INVALID_INPUT'; payload: Record<string, never> }
) & { requestId: string }

export function keys(value: unknown, names: string[]): value is Record<string, unknown> {
  return (
    isRecord(value) &&
    Object.keys(value).length === names.length &&
    names.every((name) => Object.hasOwn(value, name))
  )
}
export function jsonObject(value: unknown): value is Record<string, unknown> {
  if (!isRecord(value)) return false
  const stack = [{ value, depth: 0 }]
  let nodes = 0
  while (stack.length) {
    const item = stack.pop()!
    // Any JSON within the byte cap necessarily has fewer nodes than bytes.
    // Depth matches Jackson's default parser ceiling, not a smaller UI-only schema.
    if (++nodes > 256 * 1024 || item.depth > 1000) return false
    const v: unknown = item.value
    if (v === null || typeof v === 'string' || typeof v === 'boolean') continue
    if (typeof v === 'number') {
      if (!Number.isFinite(v)) return false
      continue
    }
    if (
      !Array.isArray(v) &&
      (!isRecord(v) || ![Object.prototype, null].includes(Object.getPrototypeOf(v)))
    )
      return false
    const values = Object.values(v)
    if (values.length + stack.length > 256 * 1024) return false
    for (const child of values) stack.push({ value: child, depth: item.depth + 1 })
  }
  return new TextEncoder().encode(JSON.stringify(value)).length <= 256 * 1024
}
export function parseRequest(value: unknown, channelId: string): RuntimeRequest | null {
  if (
    !keys(value, ['protocol', 'channelId', 'requestId', 'type', 'payload']) ||
    value.protocol !== PROTOCOL ||
    value.channelId !== channelId ||
    typeof value.requestId !== 'string' ||
    !UUID.test(value.requestId)
  )
    return null
  const { type, payload, requestId } = value
  if ((type === 'STATE_GET' || type === 'RUNTIME_READY') && keys(payload, []))
    return { type, payload: {}, requestId }
  if (
    type === 'RUNTIME_ERROR' &&
    keys(payload, ['message']) &&
    typeof payload.message === 'string' &&
    payload.message.length <= 1000
  )
    return { type, payload: { message: payload.message }, requestId }
  if (
    type === 'STATE_SAVE' &&
    keys(payload, ['value', 'expectedRevision']) &&
    jsonObject(payload.value) &&
    typeof payload.expectedRevision === 'number' &&
    Number.isSafeInteger(payload.expectedRevision) &&
    payload.expectedRevision >= 0 &&
    payload.expectedRevision < Number.MAX_SAFE_INTEGER
  )
    return {
      type,
      payload: { value: payload.value, expectedRevision: payload.expectedRevision },
      requestId,
    }
  if (type === 'STATE_SAVE' || type === 'STATE_GET')
    return { type: 'INVALID_INPUT', payload: {}, requestId }
  return null
}
