import { api, isRecord, protocolFailure } from './client'

export type WorkbenchCapabilities = {
  enabled: boolean
  reason: string | null
  archived: boolean
  sourceReady: boolean
  agentEnabled: boolean
  deliveryEnabled: boolean
}
export type SourceHead = {
  projectId: string
  sourceVersionId: string | null
  revision: number
  status: string
}
export type SourceVersion = {
  id: string
  versionNo: number
  treeSha256: string
  fileCount: number
  treeBytes: number
  basedOnId: string | null
  restoredFromId: string | null
  templateId: string | null
  createdAt: string
}
export type SourceEntry = { path: string; mode: number; bytes: number; sha256: string }
export type SourceFile = {
  path: string
  mode: number
  bytes: number
  content: string
  truncated: boolean
}
export type SourceDiff = {
  changes: { path: string; kind: string }[]
  preview: string
  truncated: boolean
}
export type SourceOperation = {
  id: string
  kind: string
  status: string
  resultVersionId: string | null
  errorCode: string | null
}

export const object = (v: unknown): Record<string, unknown> => (isRecord(v) ? v : protocolFailure())
export const string = (v: unknown, max = 8192): string =>
  typeof v === 'string' && v.length <= max ? v : protocolFailure()
export const nullable = <T>(v: unknown, decode: (v: unknown) => T): T | null =>
  v === null ? null : decode(v)
export const boolean = (v: unknown): boolean => (typeof v === 'boolean' ? v : protocolFailure())
export const integer = (v: unknown): number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : protocolFailure()
export const uuid = (v: unknown): string =>
  typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
    ? v
    : protocolFailure()
export const array = <T>(v: unknown, decode: (v: unknown) => T, max: number): T[] =>
  Array.isArray(v) && v.length <= max ? v.map(decode) : protocolFailure()
export const hash = (v: unknown): string =>
  typeof v === 'string' && /^[a-f0-9]{64}$/.test(v) ? v : protocolFailure()
const date = (v: unknown): string =>
  Number.isFinite(Date.parse(string(v, 64))) ? string(v, 64) : protocolFailure()
export const prefix = (project: string) =>
  `/api/projects/${encodeURIComponent(uuid(project))}/workbench`
export function read<T>(
  project: string,
  path: string,
  decode: (v: unknown) => T,
  signal?: AbortSignal,
) {
  return api.request(`${prefix(project)}${path}`, decode, { signal, timeoutMs: 35000 })
}
export function write<T>(
  project: string,
  path: string,
  body: unknown,
  key: string,
  decode: (v: unknown) => T,
  signal?: AbortSignal,
) {
  return api.request(`${prefix(project)}${path}`, decode, {
    method: 'POST',
    body,
    idempotencyKey: key,
    signal,
    timeoutMs: 35000,
  })
}
export function decodeCapabilities(v: unknown): WorkbenchCapabilities {
  const r = object(v)
  return {
    enabled: boolean(r.enabled),
    reason: nullable(r.reason, string),
    archived: boolean(r.archived),
    sourceReady: boolean(r.sourceReady),
    agentEnabled: boolean(r.agentEnabled),
    deliveryEnabled: boolean(r.deliveryEnabled),
  }
}
export function decodeHead(v: unknown): SourceHead {
  const r = object(v)
  return {
    projectId: uuid(r.projectId),
    sourceVersionId: nullable(r.sourceVersionId, uuid),
    revision: integer(r.revision),
    status: string(r.status, 64),
  }
}
export function decodeVersion(v: unknown): SourceVersion {
  const r = object(v)
  return {
    id: uuid(r.id),
    versionNo: integer(r.versionNo),
    treeSha256: hash(r.treeSha256),
    fileCount: integer(r.fileCount),
    treeBytes: integer(r.treeBytes),
    basedOnId: nullable(r.basedOnId, uuid),
    restoredFromId: nullable(r.restoredFromId, uuid),
    templateId: nullable(r.templateId, string),
    createdAt: date(r.createdAt),
  }
}
export function decodeVersions(v: unknown) {
  const r = object(v)
  return {
    items: array(r.items, decodeVersion, 50),
    nextBeforeVersion: nullable(r.nextBeforeVersion, integer),
  }
}
export function decodeTree(v: unknown): SourceEntry[] {
  return array(
    v,
    (entry) => {
      const r = object(entry)
      return {
        path: string(r.path, 240),
        mode: integer(r.mode),
        bytes: integer(r.bytes),
        sha256: hash(r.sha256),
      }
    },
    512,
  )
}
export function decodeFile(v: unknown): SourceFile {
  const r = object(v)
  return {
    path: string(r.path, 240),
    mode: integer(r.mode),
    bytes: integer(r.bytes),
    content: string(r.content, 256 << 10),
    truncated: boolean(r.truncated),
  }
}
export function decodeDiff(v: unknown): SourceDiff {
  const r = object(v)
  return {
    changes: array(
      r.changes,
      (entry) => {
        const c = object(entry)
        return { path: string(c.path, 240), kind: string(c.kind, 32) }
      },
      1024,
    ),
    preview: string(r.preview, 128 << 10),
    truncated: boolean(r.truncated),
  }
}
export function decodeOperation(v: unknown): SourceOperation {
  const r = object(v)
  return {
    id: uuid(r.id),
    kind: string(r.kind, 32),
    status: string(r.status, 64),
    resultVersionId: nullable(r.resultVersionId, uuid),
    errorCode: nullable(r.errorCode, string),
  }
}
export const capabilities = (project: string, signal?: AbortSignal) =>
  read(project, '/capabilities', decodeCapabilities, signal)
