import { api, isRecord, protocolFailure } from './client'
import { decodeState } from './preview'
import { SLUG, UUID } from './publication'
import { jsonObject } from '../runtime/protocol'

export type PublicApp = {
  slug: string
  versionId: string
  versionNo: number
  title: string
  summary: string
  stateSchemaVersion: 1
  initialState: Record<string, unknown>
}
const path = (slug: string) => `/api/public/apps/${encodeURIComponent(slug)}`
export function getPublicApp(slug: string) {
  return api.request(path(slug), (value): PublicApp => {
    if (
      !isRecord(value) ||
      value.slug !== slug ||
      !SLUG.test(slug) ||
      typeof value.versionId !== 'string' ||
      !UUID.test(value.versionId) ||
      typeof value.versionNo !== 'number' ||
      !Number.isSafeInteger(value.versionNo) ||
      value.versionNo < 1 ||
      typeof value.title !== 'string' ||
      typeof value.summary !== 'string' ||
      value.stateSchemaVersion !== 1 ||
      !jsonObject(value.initialState)
    )
      return protocolFailure()
    return {
      slug,
      versionId: value.versionId,
      versionNo: value.versionNo,
      title: value.title,
      summary: value.summary,
      stateSchemaVersion: 1,
      initialState: value.initialState,
    }
  })
}
export const getPublicState = (slug: string, versionId: string) =>
  api.request(`${path(slug)}/state?${new URLSearchParams({ versionId })}`, decodeState)
export const savePublicState = (
  slug: string,
  loadedVersionId: string,
  value: Record<string, unknown>,
  expectedRevision: number,
  key: string,
) =>
  api.request(`${path(slug)}/state`, decodeState, {
    method: 'PUT',
    idempotencyKey: key,
    body: { loadedVersionId, expectedRevision, stateSchemaVersion: 1, value },
  })
