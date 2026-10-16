import { api, isRecord, protocolFailure } from './client'
export type VersionReview = {
  versionId: string
  baseVersionId: string | null
  baseVersionNo: number | null
  restoredFromId: string | null
  runId: string | null
  request: string | null
  changes: { file: string; before: string; after: string }[]
}
const nullableString = (value: unknown): value is string | null =>
  value === null || typeof value === 'string'
export function getVersionReview(projectId: string, versionId: string): Promise<VersionReview> {
  return api.request(
    `/api/projects/${encodeURIComponent(projectId)}/versions/${encodeURIComponent(versionId)}/review`,
    (value) => {
      if (
        !isRecord(value) ||
        value.versionId !== versionId ||
        !nullableString(value.baseVersionId) ||
        !nullableString(value.restoredFromId) ||
        !nullableString(value.runId) ||
        !nullableString(value.request) ||
        !(
          value.baseVersionNo === null ||
          (typeof value.baseVersionNo === 'number' &&
            Number.isSafeInteger(value.baseVersionNo) &&
            value.baseVersionNo > 0)
        ) ||
        !Array.isArray(value.changes) ||
        value.changes.length > 4
      )
        return protocolFailure()
      const changes = value.changes.map((item) => {
        if (
          !isRecord(item) ||
          typeof item.file !== 'string' ||
          !['view.html', 'styles.css', 'main.js', 'manifest.json'].includes(item.file) ||
          typeof item.before !== 'string' ||
          typeof item.after !== 'string'
        )
          return protocolFailure()
        return { file: item.file, before: item.before, after: item.after }
      })
      return {
        versionId,
        baseVersionId: value.baseVersionId,
        baseVersionNo: value.baseVersionNo,
        restoredFromId: value.restoredFromId,
        runId: value.runId,
        request: value.request,
        changes,
      }
    },
  )
}
