import { api, isRecord, protocolFailure } from './client'
export type Draft = { prompt: string; revision: number }
export type DraftWrite = { prompt: string; expectedRevision: number }
function decode(value: unknown): Draft {
  if (
    !isRecord(value) ||
    typeof value.prompt !== 'string' ||
    value.prompt.length > 8000 ||
    typeof value.revision !== 'number' ||
    !Number.isSafeInteger(value.revision) ||
    value.revision < 0
  )
    return protocolFailure()
  return { prompt: value.prompt, revision: value.revision }
}
export function readDraft(project: string) {
  return api.request(`/api/projects/${encodeURIComponent(project)}/draft`, decode)
}
export function saveDraft(project: string, body: DraftWrite) {
  return api.request(`/api/projects/${encodeURIComponent(project)}/draft`, decode, {
    method: 'PUT',
    body,
  })
}
