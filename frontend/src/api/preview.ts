import { api, isRecord, protocolFailure } from './client'
import { jsonObject, type StateDocument } from '../runtime/protocol'

export function decodeState(value: unknown): StateDocument {
  if (
    !isRecord(value) ||
    !jsonObject(value.value) ||
    typeof value.revision !== 'number' ||
    !Number.isSafeInteger(value.revision) ||
    value.revision < 0 ||
    value.stateSchemaVersion !== 1
  )
    return protocolFailure()
  return { value: value.value, revision: value.revision, stateSchemaVersion: 1 }
}
export function getPreviewState(projectId: string, versionId: string) {
  return api.request(
    `/api/projects/${encodeURIComponent(projectId)}/preview-state?${new URLSearchParams({ versionId })}`,
    decodeState,
  )
}
export function savePreviewState(
  projectId: string,
  loadedVersionId: string,
  value: Record<string, unknown>,
  expectedRevision: number,
  idempotencyKey: string,
) {
  return api.request(`/api/projects/${encodeURIComponent(projectId)}/preview-state`, decodeState, {
    method: 'PUT',
    body: { loadedVersionId, expectedRevision, stateSchemaVersion: 1, value },
    idempotencyKey,
  })
}
