import { api, isRecord, protocolFailure } from './client'

export type Publication = {
  slug: string | null
  versionId: string | null
  versionNo: number | null
  status: 'PUBLISHED' | 'UNPUBLISHED'
  revision: number
  publicPath: string | null
  publicUrl: string | null
}
export type PublishInput = {
  versionId: string
  expectedProjectRevision: number
  expectedPublicationRevision: number
}
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const SLUG = /^[0-9a-f]{32}$/
function decode(value: unknown): Publication {
  if (
    !isRecord(value) ||
    !['PUBLISHED', 'UNPUBLISHED'].includes(String(value.status)) ||
    typeof value.revision !== 'number' ||
    !Number.isSafeInteger(value.revision) ||
    value.revision < 0
  )
    return protocolFailure()
  if (value.slug === null) {
    if (
      value.status !== 'UNPUBLISHED' ||
      value.revision !== 0 ||
      value.versionId !== null ||
      value.versionNo !== null ||
      value.publicPath !== null ||
      value.publicUrl !== null
    )
      return protocolFailure()
    return {
      slug: null,
      versionId: null,
      versionNo: null,
      status: 'UNPUBLISHED',
      revision: 0,
      publicPath: null,
      publicUrl: null,
    }
  }
  if (
    typeof value.slug !== 'string' ||
    !SLUG.test(value.slug) ||
    value.revision < 1 ||
    typeof value.versionId !== 'string' ||
    !UUID.test(value.versionId) ||
    typeof value.versionNo !== 'number' ||
    !Number.isSafeInteger(value.versionNo) ||
    value.versionNo < 1 ||
    value.publicPath !== `/a/${value.slug}` ||
    !(value.publicUrl === null || typeof value.publicUrl === 'string')
  )
    return protocolFailure()
  if (typeof value.publicUrl === 'string') {
    let url: URL
    try {
      url = new URL(value.publicUrl)
    } catch {
      return protocolFailure()
    }
    if (
      !(
        url.protocol === 'https:' ||
        (url.protocol === 'http:' &&
          (['localhost', '127.0.0.1'].includes(url.hostname) || url.origin === location.origin))
      ) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      url.pathname !== value.publicPath
    )
      return protocolFailure()
  }
  return {
    slug: value.slug,
    versionId: value.versionId,
    versionNo: value.versionNo,
    status: value.status as Publication['status'],
    revision: value.revision,
    publicPath: value.publicPath,
    publicUrl: value.publicUrl,
  }
}
const path = (project: string) => `/api/projects/${encodeURIComponent(project)}/publication`
export const getPublication = (project: string) => api.request(path(project), decode)
export const publish = (project: string, body: PublishInput, key: string) =>
  api.request(path(project), decode, { method: 'PUT', body, idempotencyKey: key })
export const unpublish = (project: string, revision: number, key: string) =>
  api.request(
    `${path(project)}?expectedPublicationRevision=${revision}`,
    (value): void => {
      if (value !== undefined) protocolFailure()
    },
    { method: 'DELETE', idempotencyKey: key },
  )
