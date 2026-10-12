import { api, isRecord, protocolFailure } from './client'

const statuses = [
  'QUEUED',
  'BUILDING',
  'CANCELLING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
] as const
const stages = [
  'QUEUED',
  'PREPARING',
  'INSTALLING',
  'CHECKING_FRONTEND',
  'BUILDING_FRONTEND',
  'CHECKING_BACKEND',
  'VERIFYING',
  'COMPLETE',
] as const
const errors = [
  'BUILD_FAILED',
  'BUILD_TIMEOUT',
  'BUILD_INPUT_CHANGED',
  'BUILD_INTERRUPTED',
  'BUILD_CANCELLED',
  'BUILD_CAPACITY_EXCEEDED',
  'BUILD_ENVIRONMENT_NOT_READY',
  'INVALID_ENGINEERING_BUNDLE',
  'BUILD_ARTIFACT_UNAVAILABLE',
] as const
export type BuildStatus = (typeof statuses)[number]
export type BuildStage = (typeof stages)[number]
export type EngineeringBuild = {
  id: string
  projectId: string
  versionId: string
  status: BuildStatus
  stage: BuildStage
  templateDigest: string
  buildPolicyVersion: 'native-v1'
  bundleId: string | null
  archiveSha256: string | null
  loadedRuntimeId: string | null
  jarSha256: string | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  lastEventSeq: number
}
export type BuildEvent = {
  seq: number
  type: string
  status: BuildStatus
  stage: BuildStage
  errorCode: string | null
  createdAt: string
}
function member<T extends string>(values: readonly T[], value: unknown): value is T {
  return values.some((item) => item === value)
}
function uuid(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
}
function hash(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
}
function time(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
  )
}
export function buildActive(build: EngineeringBuild | null) {
  return !!build && ['QUEUED', 'BUILDING', 'CANCELLING'].includes(build.status)
}
export function decodeBuild(value: unknown): EngineeringBuild {
  if (
    !isRecord(value) ||
    Object.keys(value).length !== 17 ||
    !uuid(value.id) ||
    !uuid(value.projectId) ||
    !uuid(value.versionId) ||
    !member(statuses, value.status) ||
    !member(stages, value.stage) ||
    !hash(value.templateDigest) ||
    value.buildPolicyVersion !== 'native-v1' ||
    !(value.bundleId === null || hash(value.bundleId)) ||
    !(value.archiveSha256 === null || hash(value.archiveSha256)) ||
    !(value.loadedRuntimeId === null || hash(value.loadedRuntimeId)) ||
    !(value.jarSha256 === null || hash(value.jarSha256)) ||
    !(value.errorCode === null || member(errors, value.errorCode)) ||
    !(
      value.errorMessage === null ||
      (typeof value.errorMessage === 'string' && value.errorMessage.length <= 256)
    ) ||
    !time(value.createdAt) ||
    !(value.startedAt === null || time(value.startedAt)) ||
    !(value.finishedAt === null || time(value.finishedAt)) ||
    typeof value.lastEventSeq !== 'number' ||
    !Number.isSafeInteger(value.lastEventSeq) ||
    value.lastEventSeq < 1 ||
    value.lastEventSeq > 1000
  )
    return protocolFailure()
  const result: EngineeringBuild = {
    id: value.id,
    projectId: value.projectId,
    versionId: value.versionId,
    status: value.status,
    stage: value.stage,
    templateDigest: value.templateDigest,
    buildPolicyVersion: value.buildPolicyVersion,
    bundleId: value.bundleId,
    archiveSha256: value.archiveSha256,
    loadedRuntimeId: value.loadedRuntimeId,
    jarSha256: value.jarSha256,
    errorCode: value.errorCode,
    errorMessage: value.errorMessage,
    createdAt: value.createdAt,
    startedAt: value.startedAt,
    finishedAt: value.finishedAt,
    lastEventSeq: value.lastEventSeq,
  }
  if (
    buildActive(result) !== (result.finishedAt === null) ||
    (result.bundleId === null) !== (result.archiveSha256 === null) ||
    (['BUILDING', 'CANCELLING'].includes(result.status) && result.startedAt === null) ||
    (result.status === 'SUCCEEDED' &&
      (!result.startedAt ||
        !result.bundleId ||
        !result.archiveSha256 ||
        !result.loadedRuntimeId ||
        !result.jarSha256 ||
        result.errorCode !== null ||
        result.stage !== 'COMPLETE'))
  )
    return protocolFailure()
  return result
}
function base(project: string) {
  if (!uuid(project)) return protocolFailure()
  return `/api/projects/${project}/builds`
}
function target(project: string, build: string) {
  if (!uuid(build)) return protocolFailure()
  return `${base(project)}/${build}`
}
export function getBuild(project: string, build: string, signal: AbortSignal) {
  return api.request(
    target(project, build),
    (value) => {
      const result = decodeBuild(value)
      if (result.projectId !== project || result.id !== build) return protocolFailure()
      return result
    },
    { signal },
  )
}
export function listBuilds(project: string, signal: AbortSignal, cursor: string | null = null) {
  const query = new URLSearchParams({ limit: '20' })
  if (cursor) query.set('cursor', cursor)
  return api.request(
    `${base(project)}?${query}`,
    (value) => {
      if (
        !isRecord(value) ||
        !Array.isArray(value.items) ||
        value.items.length > 20 ||
        !(
          value.nextCursor === null ||
          (typeof value.nextCursor === 'string' && value.nextCursor.length <= 160)
        )
      )
        return protocolFailure()
      const items = value.items.map(decodeBuild)
      if (
        items.some((item) => item.projectId !== project) ||
        new Set(items.map((item) => item.id)).size !== items.length
      )
        return protocolFailure()
      return { items, nextCursor: value.nextCursor }
    },
    { signal },
  )
}
export function submitBuild(project: string, version: string, key: string, signal: AbortSignal) {
  if (!uuid(version)) return protocolFailure()
  return api.request(
    base(project),
    (value) => {
      if (
        !isRecord(value) ||
        Object.keys(value).length !== 3 ||
        !uuid(value.buildId) ||
        value.projectId !== project ||
        value.versionId !== version
      )
        return protocolFailure()
      return { buildId: value.buildId, projectId: project, versionId: version }
    },
    { method: 'POST', body: { versionId: version }, idempotencyKey: key, signal },
  )
}
export function cancelBuild(project: string, build: string, key: string, signal: AbortSignal) {
  return api.request(
    `${target(project, build)}/cancellations`,
    (value) => {
      if (!isRecord(value) || Object.keys(value).length !== 1 || value.buildId !== build)
        return protocolFailure()
      return { buildId: build }
    },
    { method: 'POST', body: {}, idempotencyKey: key, signal },
  )
}
export function listBuildEvents(
  project: string,
  build: string,
  after: number,
  signal: AbortSignal,
) {
  return api.request(
    `${target(project, build)}/events?after=${after}&limit=100`,
    (value) => {
      if (!isRecord(value) || !Array.isArray(value.items) || value.items.length > 100)
        return protocolFailure()
      let previous = after
      const items = value.items.map((item): BuildEvent => {
        if (
          !isRecord(item) ||
          Object.keys(item).length !== 6 ||
          typeof item.seq !== 'number' ||
          !Number.isSafeInteger(item.seq) ||
          item.seq <= previous ||
          item.seq > 1000 ||
          !member(
            ['build.queued', 'build.stage', 'build.cancelling', 'build.completed'],
            item.type,
          ) ||
          !member(statuses, item.status) ||
          !member(stages, item.stage) ||
          !(item.errorCode === null || member(errors, item.errorCode)) ||
          !time(item.createdAt)
        )
          return protocolFailure()
        previous = item.seq
        return {
          seq: item.seq,
          type: item.type,
          status: item.status,
          stage: item.stage,
          errorCode: item.errorCode,
          createdAt: item.createdAt,
        }
      })
      if (value.nextCursor !== previous) return protocolFailure()
      return { items, nextCursor: previous }
    },
    { signal },
  )
}
