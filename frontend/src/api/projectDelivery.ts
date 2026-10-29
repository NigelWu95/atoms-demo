import { api, protocolFailure } from './client'
import { hash, integer, nullable, object, string, uuid } from './projectWorkbench'

export type DeliveryIdentity = {
  projectId: string
  buildId: string
  sourceVersionId: string
  treeSha256: string
}
export type DeliveryPreview = {
  previewId: string
  expiresAt: string
  iframeUrl: string
  runId: string
  identity: DeliveryIdentity
}
export type LocalApplicationAddress = {
  runId: string
  buildId: string
  sourceVersionId: string
  url: string
}
export function decodeLocalAddress(v: unknown): LocalApplicationAddress {
  const r = object(v),
    url = string(r.url, 128)
  if (!/^http:\/\/(localhost|127\.0\.0\.1):[1-9][0-9]{3,4}\/$/.test(url)) return protocolFailure()
  const port = Number(new URL(url).port)
  if (port < 1024 || port > 65535) return protocolFailure()
  return {
    runId: uuid(r.runId),
    buildId: uuid(r.buildId),
    sourceVersionId: uuid(r.sourceVersionId),
    url,
  }
}
export function getLocalAddress(project: string, run: string, signal: AbortSignal) {
  return api.request(`${prefix(project)}/runs/${uuid(run)}/address`, decodeLocalAddress, {
    signal,
    timeoutMs: 35000,
  })
}
const prefix = (project: string) => `/api/projects/${uuid(project)}/delivery`
function decodeIdentity(v: unknown): DeliveryIdentity {
  const r = object(v)
  return {
    projectId: uuid(r.projectId),
    buildId: uuid(r.buildId),
    sourceVersionId: uuid(r.sourceVersionId),
    treeSha256: hash(r.treeSha256),
  }
}
export function decodePreview(v: unknown): DeliveryPreview {
  const r = object(v),
    identity = object(r.identity)
  const url = string(r.iframeUrl, 128),
    expiresAt = string(r.expiresAt, 64)
  if (
    !/^\/api\/delivery\/preview\/[A-Za-z0-9_-]{43}\/$/.test(url) ||
    !Number.isFinite(Date.parse(expiresAt))
  )
    return protocolFailure()
  return {
    previewId: uuid(r.previewId),
    expiresAt,
    iframeUrl: url,
    runId: uuid(identity.runId),
    identity: decodeIdentity(identity.build),
  }
}
export function openPreview(project: string, run: string, signal: AbortSignal) {
  return api.request(`${prefix(project)}/runs/${uuid(run)}/previews`, decodePreview, {
    method: 'POST',
    body: {},
    signal,
    timeoutMs: 35000,
  })
}
export function revokePreview(project: string, preview: string) {
  return api.request(`${prefix(project)}/previews/${uuid(preview)}`, () => undefined, {
    method: 'DELETE',
    body: {},
  })
}
export async function downloadSource(
  project: string,
  kind: 'builds' | 'previews',
  id: string,
  signal: AbortSignal,
) {
  const { bytes, type } = await api.binary(
    `${prefix(project)}/${kind}/${uuid(id)}/source.zip`,
    signal,
    20 << 20,
  )
  if (
    type?.split(';')[0]?.trim().toLowerCase() !== 'application/zip' ||
    bytes[0] !== 80 ||
    bytes[1] !== 75 ||
    bytes[2] !== 3 ||
    bytes[3] !== 4
  )
    return protocolFailure()
  signal.throwIfAborted()
  return new Blob([bytes], { type: 'application/zip' })
}
export function restoreSource(
  project: string,
  target: string,
  expected: string,
  revision: number,
  key: string,
  signal: AbortSignal,
) {
  return api.request(
    `${prefix(project)}/restorations`,
    (v) => {
      const r = object(v)
      return {
        id: uuid(r.operationId),
        kind: 'RESTORE',
        status: string(r.status, 64),
        resultVersionId: nullable(r.resultVersionId, uuid),
        errorCode: nullable(r.errorCode, string),
      }
    },
    {
      method: 'POST',
      body: {
        targetSourceVersionId: uuid(target),
        expectedSourceVersionId: uuid(expected),
        expectedRevision: integer(revision),
      },
      idempotencyKey: key,
      signal,
      timeoutMs: 35000,
    },
  )
}
