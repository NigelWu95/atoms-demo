import { api, isRecord, protocolFailure } from './client'
import { decodeState } from './preview'
import type { StateRequest } from '../runtime/protocol'

const statuses = [
  'QUEUED',
  'STARTING',
  'RUNNING',
  'STOPPING',
  'STOPPED',
  'FAILED',
  'INTERRUPTED',
] as const
export type InstanceStatus = (typeof statuses)[number]
export type AppInstance = {
  id: string
  projectId: string
  buildId: string
  versionId: string
  status: InstanceStatus
  loadedRuntimeId: string
  errorCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
const time = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^\d{4}-\d\d-\d\dT.*Z$/.test(value) &&
  Number.isFinite(Date.parse(value))
export function instanceActive(value: AppInstance) {
  return ['QUEUED', 'STARTING', 'RUNNING', 'STOPPING'].includes(value.status)
}
export function decodeInstance(value: unknown): AppInstance {
  if (
    !isRecord(value) ||
    Object.keys(value).length !== 10 ||
    !uuid(value.id) ||
    !uuid(value.projectId) ||
    !uuid(value.buildId) ||
    !uuid(value.versionId) ||
    !statuses.some((status) => status === value.status) ||
    typeof value.loadedRuntimeId !== 'string' ||
    !/^[a-f0-9]{64}$/.test(value.loadedRuntimeId) ||
    !(
      value.errorCode === null ||
      (typeof value.errorCode === 'string' && /^[A-Z_]{1,48}$/.test(value.errorCode))
    ) ||
    !time(value.createdAt) ||
    !(value.startedAt === null || time(value.startedAt)) ||
    !(value.finishedAt === null || time(value.finishedAt))
  )
    return protocolFailure()
  const result = value as AppInstance
  if (
    instanceActive(result) !== (result.finishedAt === null) ||
    (['STARTING', 'RUNNING', 'STOPPING'].includes(result.status) && !result.startedAt)
  )
    return protocolFailure()
  return result
}
function base(project: string) {
  if (!uuid(project)) return protocolFailure()
  return `/api/projects/${project}/instances`
}
export function instancePath(project: string, id: string) {
  if (!uuid(id)) return protocolFailure()
  return `${base(project)}/${id}`
}
export function listInstances(project: string, signal: AbortSignal) {
  return api.request(
    base(project),
    (value) => {
      if (!isRecord(value) || !Array.isArray(value.items) || value.items.length > 20)
        return protocolFailure()
      const items = value.items.map(decodeInstance)
      if (items.some((item) => item.projectId !== project)) return protocolFailure()
      return items
    },
    { signal },
  )
}
function accepted(value: unknown) {
  if (!isRecord(value) || Object.keys(value).length !== 1 || !uuid(value.instanceId))
    return protocolFailure()
  return { instanceId: value.instanceId }
}
export function startInstance(project: string, buildId: string, key: string, signal: AbortSignal) {
  if (!uuid(buildId)) return protocolFailure()
  return api.request(base(project), accepted, {
    method: 'POST',
    body: { buildId },
    idempotencyKey: key,
    signal,
  })
}
export function stopInstance(project: string, id: string, key: string, signal: AbortSignal) {
  return api.request(
    `${instancePath(project, id)}/stops`,
    (value) => {
      const result = accepted(value)
      if (result.instanceId !== id) return protocolFailure()
      return result
    },
    { method: 'POST', body: {}, idempotencyKey: key, signal },
  )
}
export function loadInstanceApp(instance: AppInstance, signal: AbortSignal) {
  return api.request(
    `${instancePath(instance.projectId, instance.id)}/app`,
    (value) => {
      if (!isRecord(value) || value.loadedRuntimeId !== instance.loadedRuntimeId)
        return protocolFailure()
      return instance
    },
    { signal },
  )
}
export function instanceState(
  instance: AppInstance,
  request: StateRequest,
  key: string,
  signal: AbortSignal,
) {
  return api.request(
    `${instancePath(instance.projectId, instance.id)}/state`,
    (value) => {
      const state = decodeState(value)
      if (request.type === 'STATE_SAVE' && state.revision !== request.payload.expectedRevision + 1)
        return protocolFailure()
      return state
    },
    request.type === 'STATE_GET'
      ? { signal }
      : {
          method: 'PUT',
          signal,
          idempotencyKey: key,
          body: {
            loadedRuntimeId: instance.loadedRuntimeId,
            ...request.payload,
            stateSchemaVersion: 1,
          },
        },
  )
}
