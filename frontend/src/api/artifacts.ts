import { api, isRecord, protocolFailure } from './client'
import { jsonObject } from '../runtime/protocol'

export type Artifact = {
  formatVersion: 1
  manifest: {
    title: string
    summary: string
    runtime: 'web-static-v1'
    stateSchemaVersion: 1
    initialState: Record<string, unknown>
  }
  files: { 'view.html': string; 'styles.css': string; 'main.js': string }
}
export type VersionSummary = { id: string; projectId: string; versionNo: number; createdAt: string }
export type Version = VersionSummary & { artifact: Artifact }
function summary(value: unknown): VersionSummary {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    typeof value.projectId !== 'string' ||
    typeof value.versionNo !== 'number' ||
    !Number.isSafeInteger(value.versionNo) ||
    value.versionNo < 1 ||
    typeof value.createdAt !== 'string'
  )
    return protocolFailure()
  return {
    id: value.id,
    projectId: value.projectId,
    versionNo: value.versionNo,
    createdAt: value.createdAt,
  }
}
export function getVersion(projectId: string, versionId: string): Promise<Version> {
  return api.request(
    `/api/projects/${encodeURIComponent(projectId)}/versions/${encodeURIComponent(versionId)}`,
    (value) => {
      const meta = summary(value)
      if (!isRecord(value) || !isRecord(value.artifact)) return protocolFailure()
      const { formatVersion, manifest, files } = value.artifact
      if (
        formatVersion !== 1 ||
        !isRecord(manifest) ||
        manifest.runtime !== 'web-static-v1' ||
        manifest.stateSchemaVersion !== 1 ||
        typeof manifest.title !== 'string' ||
        typeof manifest.summary !== 'string' ||
        !jsonObject(manifest.initialState) ||
        !isRecord(files) ||
        typeof files['view.html'] !== 'string' ||
        typeof files['styles.css'] !== 'string' ||
        typeof files['main.js'] !== 'string'
      )
        return protocolFailure()
      return {
        ...meta,
        artifact: {
          formatVersion: 1,
          manifest: {
            title: manifest.title,
            summary: manifest.summary,
            runtime: 'web-static-v1',
            stateSchemaVersion: 1,
            initialState: manifest.initialState,
          },
          files: {
            'view.html': files['view.html'],
            'styles.css': files['styles.css'],
            'main.js': files['main.js'],
          },
        },
      }
    },
  )
}
export function listVersions(
  projectId: string,
  cursor: string | null = null,
): Promise<{ items: VersionSummary[]; nextCursor: string | null }> {
  const query = new URLSearchParams({ limit: '20' })
  if (cursor) query.set('cursor', cursor)
  return api.request(
    `/api/projects/${encodeURIComponent(projectId)}/versions?${query}`,
    (value) => {
      if (
        !isRecord(value) ||
        !Array.isArray(value.items) ||
        !(value.nextCursor === null || typeof value.nextCursor === 'string')
      )
        return protocolFailure()
      return { items: value.items.map(summary), nextCursor: value.nextCursor }
    },
  )
}

export type RestoreInput = { sourceVersionId: string; expectedProjectRevision: number }
export function restoreVersion(
  projectId: string,
  body: RestoreInput,
  key: string,
): Promise<VersionSummary> {
  return api.request(`/api/projects/${encodeURIComponent(projectId)}/restorations`, summary, {
    method: 'POST',
    body,
    idempotencyKey: key,
  })
}
