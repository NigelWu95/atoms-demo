export class InvalidResponse extends Error {}
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
export function keys(value: unknown, names: string[]): value is Record<string, unknown> {
  return (
    isRecord(value) &&
    Object.keys(value).length === names.length &&
    names.every((name) => Object.hasOwn(value, name))
  )
}

export async function readJson(response: Response, limit = 16 * 1024): Promise<unknown> {
  if (response.headers.get('content-type')?.split(';')[0]?.trim() !== 'application/json') {
    await response.body?.cancel()
    throw new InvalidResponse()
  }
  const reader = response.body?.getReader()
  if (!reader) throw new InvalidResponse()
  const chunks: Uint8Array[] = []
  let length = 0
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      length += value.byteLength
      if (length > limit) throw new InvalidResponse()
      chunks.push(value)
    }
  } finally {
    await reader.cancel()
    reader.releaseLock()
  }
  try {
    const bytes = new Uint8Array(length)
    let offset = 0
    for (const chunk of chunks) {
      bytes.set(chunk, offset)
      offset += chunk.byteLength
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    throw new InvalidResponse()
  }
}

export type AppDescription = {
  title: string
  summary: string
  runtime: 'web-static-v1'
  stateSchemaVersion: 1
  loadedRuntimeId: string
}
function text(value: unknown, max: number): value is string {
  return (
    typeof value === 'string' &&
    value.trim().length > 0 &&
    value.length <= max &&
    !/[\u0000-\u001f\u007f-\u009f]/u.test(value)
  )
}
export function appDescription(value: unknown): AppDescription {
  if (
    !keys(value, ['title', 'summary', 'runtime', 'stateSchemaVersion', 'loadedRuntimeId']) ||
    !text(value.title, 120) ||
    !text(value.summary, 2000) ||
    value.runtime !== 'web-static-v1' ||
    value.stateSchemaVersion !== 1 ||
    typeof value.loadedRuntimeId !== 'string' ||
    value.loadedRuntimeId.length !== 64 ||
    !/^[0-9a-f]+$/.test(value.loadedRuntimeId)
  )
    throw new InvalidResponse()
  return {
    title: value.title,
    summary: value.summary,
    runtime: value.runtime,
    stateSchemaVersion: 1,
    loadedRuntimeId: value.loadedRuntimeId,
  }
}
export function csrfToken(value: unknown): string {
  if (
    !keys(value, ['token', 'headerName']) ||
    value.headerName !== 'X-CSRF-TOKEN' ||
    typeof value.token !== 'string' ||
    value.token.length < 32 ||
    value.token.length > 256 ||
    /[^A-Za-z0-9_=-]/.test(value.token)
  )
    throw new InvalidResponse()
  return value.token
}
export function guestIdentity(value: unknown): void {
  if (
    !keys(value, ['userId', 'isGuest']) ||
    value.isGuest !== true ||
    typeof value.userId !== 'string' ||
    value.userId.length !== 36 ||
    !UUID.test(value.userId)
  )
    throw new InvalidResponse()
}
