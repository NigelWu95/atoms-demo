import { api, isRecord, protocolFailure } from './client'

export type Project = {
  id: string
  name: string
  ownerId: string
  projectRevision: number
  currentVersionId?: string | null
  currentSourceVersionId?: string | null
  createdAt: string
  updatedAt: string
  archived?: boolean
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
    currentVersionId:
      value.currentVersionId === undefined || value.currentVersionId === null
        ? null
        : typeof value.currentVersionId === 'string'
          ? value.currentVersionId
          : protocolFailure(),
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    archived:
      value.archived === undefined
        ? false
        : typeof value.archived === 'boolean'
          ? value.archived
          : protocolFailure(),
  }
}
export function listProjects(
  limit = 20,
  cursor: string | null = null,
  options?: { q: string; sort: 'created' | 'updated'; archived: boolean },
): Promise<ProjectPage> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (cursor) params.set('cursor', cursor)
  if (options) {
    params.set('q', options.q)
    params.set('sort', options.sort)
    params.set('archived', String(options.archived))
  }
  return api.request(`/api/projects?${params}`, (value) => {
    if (
      !isRecord(value) ||
      !Array.isArray(value.items) ||
      !(value.nextCursor === null || typeof value.nextCursor === 'string')
    )
      return protocolFailure()
    const versions = value.sourceVersions === undefined ? {} : value.sourceVersions
    if (!isRecord(versions)) return protocolFailure()
    const items = value.items.map((item) => {
      const project = decodeProject(item),
        version = versions[project.id]
      if (version !== undefined && typeof version !== 'string') return protocolFailure()
      return { ...project, currentSourceVersionId: version ?? null }
    })
    return { items, nextCursor: value.nextCursor }
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
export function archiveProject(id: string, archived: boolean): Promise<Project> {
  return api.request(`/api/projects/${encodeURIComponent(id)}/archive`, decodeProject, {
    method: archived ? 'POST' : 'DELETE',
    body: {},
  })
}
