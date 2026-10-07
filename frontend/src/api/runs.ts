import { api, isRecord, protocolFailure } from './client'

const statuses = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'] as const
export type RunStatus = (typeof statuses)[number]
export type Run = {
  id: string
  projectId: string
  prompt: string
  status: RunStatus
  stage: string
  resultVersionId: string | null
  errorCode: string | null
  errorMessage: string | null
  lastEventSeq: number
}
export type CreateRun = {
  prompt: string
  intent: 'CREATE' | 'MODIFY'
  baseVersionId: string | null
  expectedProjectRevision: number
}
export type Message = {
  id: string
  runId: string
  role: string
  content: string
  createdAt: string
}
export type Availability = {
  configured: boolean
  workerEnabled: boolean
  remainingRequests: number
  model: string
}
export function decodeRun(value: unknown): Run {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    typeof value.projectId !== 'string' ||
    typeof value.prompt !== 'string' ||
    !statuses.some((status) => status === value.status) ||
    typeof value.stage !== 'string' ||
    !(value.resultVersionId === null || typeof value.resultVersionId === 'string') ||
    (value.status === 'SUCCEEDED' && !value.resultVersionId) ||
    !(value.errorCode === null || typeof value.errorCode === 'string') ||
    !(value.errorMessage === null || typeof value.errorMessage === 'string') ||
    typeof value.lastEventSeq !== 'number' ||
    !Number.isSafeInteger(value.lastEventSeq) ||
    value.lastEventSeq < 0
  )
    return protocolFailure()
  return {
    id: value.id,
    projectId: value.projectId,
    prompt: value.prompt,
    status: value.status as RunStatus,
    stage: value.stage,
    resultVersionId: value.resultVersionId,
    errorCode: value.errorCode,
    errorMessage: value.errorMessage,
    lastEventSeq: value.lastEventSeq,
  }
}
function page<T>(
  value: unknown,
  decode: (item: unknown) => T,
): { items: T[]; nextCursor: string | null } {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !(value.nextCursor === null || typeof value.nextCursor === 'string')
  )
    return protocolFailure()
  return { items: value.items.map(decode), nextCursor: value.nextCursor }
}
export function generationAvailability() {
  return api.request('/api/generation', (value): Availability => {
    if (
      !isRecord(value) ||
      typeof value.configured !== 'boolean' ||
      typeof value.workerEnabled !== 'boolean' ||
      typeof value.model !== 'string' ||
      typeof value.remainingRequests !== 'number' ||
      !Number.isSafeInteger(value.remainingRequests) ||
      value.remainingRequests < 0
    )
      return protocolFailure()
    return {
      configured: value.configured,
      workerEnabled: value.workerEnabled,
      model: value.model,
      remainingRequests: value.remainingRequests,
    }
  })
}
export function listRuns(projectId: string) {
  return api.request(`/api/projects/${encodeURIComponent(projectId)}/runs?limit=20`, (value) =>
    page(value, decodeRun),
  )
}
export function listMessages(projectId: string, cursor: string | null = null) {
  const query = new URLSearchParams({ limit: '20' })
  if (cursor) query.set('cursor', cursor)
  return api.request(`/api/projects/${encodeURIComponent(projectId)}/messages?${query}`, (value) =>
    page(value, (item): Message => {
      if (
        !isRecord(item) ||
        typeof item.id !== 'string' ||
        typeof item.runId !== 'string' ||
        typeof item.content !== 'string' ||
        typeof item.role !== 'string' ||
        !['USER', 'PLANNER', 'BUILDER', 'SYSTEM'].includes(item.role) ||
        typeof item.createdAt !== 'string' ||
        !Number.isFinite(Date.parse(item.createdAt))
      )
        return protocolFailure()
      return {
        id: item.id,
        runId: item.runId,
        role: item.role,
        content: item.content,
        createdAt: item.createdAt,
      }
    }),
  )
}
export function submitRun(projectId: string, body: CreateRun, idempotencyKey: string) {
  return api.request(
    `/api/projects/${encodeURIComponent(projectId)}/runs`,
    (value) => {
      if (!isRecord(value) || typeof value.runId !== 'string' || value.status !== 'QUEUED')
        return protocolFailure()
      return { runId: value.runId }
    },
    { method: 'POST', body, idempotencyKey },
  )
}
export function cancelRun(id: string) {
  return api.request(`/api/runs/${encodeURIComponent(id)}/cancellation`, decodeRun, {
    method: 'PUT',
    body: {},
  })
}
export function isActive(run: Run | null) {
  return run?.status === 'QUEUED' || run?.status === 'RUNNING'
}
export function runLabel(run: Run | null): string {
  if (!run) return '准备开始'
  return (
    (
      {
        QUEUED: '等待执行',
        PLANNING: '正在规划需求',
        BUILDING: '正在生成代码',
        REPAIRING: '正在修复产物',
        SUCCEEDED: '代码已生成',
        FAILED: '生成失败',
        CANCELLED: '已取消',
        INTERRUPTED: '生成已中断',
      } as Record<string, string>
    )[run.stage] ?? '正在执行'
  )
}
