import { api, isRecord, protocolFailure } from './client'

export type Project = {
  id: string
  name: string
  ownerId: string
  projectRevision: number
  createdAt: string
  updatedAt: string
}
export type ProjectPage = { items: Project[]; nextCursor: string | null }
function decodeProject(value: unknown): Project {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    typeof value.name !== 'string' ||
    typeof value.ownerId !== 'string' ||
    typeof value.projectRevision !== 'number' ||
    !Number.isSafeInteger(value.projectRevision) ||
    value.projectRevision < 0 ||
    typeof value.createdAt !== 'string' ||
    !Number.isFinite(Date.parse(value.createdAt)) ||
    typeof value.updatedAt !== 'string' ||
    !Number.isFinite(Date.parse(value.updatedAt))
  )
    return protocolFailure()
  return {
    id: value.id,
    name: value.name,
    ownerId: value.ownerId,
    projectRevision: value.projectRevision,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}
export function listProjects(limit = 20, cursor: string | null = null): Promise<ProjectPage> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (cursor) params.set('cursor', cursor)
  return api.request(`/api/projects?${params}`, (value) => {
    if (
      !isRecord(value) ||
      !Array.isArray(value.items) ||
      !(value.nextCursor === null || typeof value.nextCursor === 'string')
    )
      return protocolFailure()
    return { items: value.items.map(decodeProject), nextCursor: value.nextCursor }
  })
}
/** Callers own the intent key and must reuse it after an unknown create result. */
export function createProject(name: string, idempotencyKey: string): Promise<Project> {
  return api.request('/api/projects', decodeProject, {
    method: 'POST',
    body: { name },
    idempotencyKey,
  })
}
export function getProject(id: string): Promise<Project> {
  return api.request(`/api/projects/${encodeURIComponent(id)}`, decodeProject)
}
export function renameProject(id: string, name: string): Promise<Project> {
  return api.request(`/api/projects/${encodeURIComponent(id)}`, decodeProject, {
    method: 'PATCH',
    body: { name },
  })
}
