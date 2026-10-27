import { array, boolean, hash, integer, nullable, object, string, uuid } from './projectWorkbench'

export type CommandResult = { id: string; status: string; resultVersionId: string | null }
export type AgentTask = CommandResult & {
  prompt: string
  baseSourceVersionId: string
  cancelRequested: boolean
  errorCode: string | null
  createdAt: string
}
export type ExecutionTask = CommandResult & {
  kind: string
  sourceVersionId: string
  buildId: string | null
  cancelRequested: boolean
  stopRequested: boolean
  executionPending: boolean
  errorCode: string | null
  finishedAt: string | null
}
export type TaskPage = { items: ExecutionTask[]; nextCursor: string | null; hasMore: boolean }
export type Migration = {
  projectId: string
  buildId: string
  sourceVersionId: string
  bindingId: string
  historySha256: string
  targetSha256: string
  decision: string
  files: { path: string; sha256: string; applied: boolean }[]
}
export type TaskEvent = { sequence: number; description: string }
export function decodeCommand(v: unknown): CommandResult {
  const r = object(v)
  return {
    id: uuid(r.id),
    status: string(r.status, 64),
    resultVersionId: r.resultVersionId === undefined ? null : nullable(r.resultVersionId, uuid),
  }
}
export function decodeAgentTasks(v: unknown): AgentTask[] {
  return array(
    v,
    (value) => {
      const r = object(value),
        t = object(r.task)
      return {
        ...decodeCommand(t),
        prompt: string(r.prompt, 8000),
        baseSourceVersionId: uuid(t.baseSourceVersionId),
        cancelRequested: boolean(t.cancelRequested),
        errorCode: nullable(t.errorCode, string),
        createdAt: string(t.createdAt, 64),
      }
    },
    100,
  )
}
export function decodeExecution(v: unknown): ExecutionTask {
  const r = object(v)
  return {
    ...decodeCommand(r),
    kind: string(r.kind, 32),
    sourceVersionId: uuid(r.sourceVersionId),
    buildId: nullable(r.buildId, uuid),
    cancelRequested: boolean(r.cancelRequested),
    stopRequested: boolean(r.stopRequested),
    executionPending: boolean(r.executionPending),
    errorCode: nullable(r.errorCode, string),
    finishedAt: nullable(r.finishedAt, string),
  }
}
export function decodeTaskPage(v: unknown): TaskPage {
  const r = object(v)
  return {
    items: array(r.items, decodeExecution, 100),
    nextCursor: nullable(r.nextCursor, string),
    hasMore: boolean(r.hasMore),
  }
}
export function decodeRuntime(v: unknown) {
  const r = object(v)
  return { coordinatorReady: boolean(r.coordinatorReady), databaseBound: boolean(r.databaseBound) }
}
export function decodeMigration(v: unknown): Migration {
  const r = object(v)
  return {
    projectId: uuid(r.projectId),
    buildId: uuid(r.buildId),
    sourceVersionId: uuid(r.sourceVersionId),
    bindingId: uuid(r.bindingId),
    historySha256: hash(r.historySha256),
    targetSha256: hash(r.targetSha256),
    decision: string(r.decision, 32),
    files: array(
      r.files,
      (file) => {
        const f = object(file)
        return { path: string(f.path, 240), sha256: hash(f.sha256), applied: boolean(f.applied) }
      },
      128,
    ),
  }
}
export function decodeApproval(v: unknown) {
  return uuid(object(v).approvalId)
}
export function decodeAgentEvents(v: unknown) {
  const r = object(v)
  return {
    items: array(
      r.events,
      (value) => {
        const e = object(value),
          p = object(e.payload),
          kind = string(e.kind, 32)
        return {
          sequence: integer(e.sequence),
          description:
            kind === 'USAGE'
              ? `输入 ${integer(p.inputTokens)} / 输出 ${integer(p.outputTokens)} tokens，读取 ${integer(p.observedPaths)} 个路径`
              : `${string(p.status, 64)}${p.code ? ` · ${string(p.code, 100)}` : ''}`,
        }
      },
      100,
    ),
    nextCursor: integer(r.nextCursor),
    hasMore: boolean(r.hasMore),
  }
}
export function decodeExecutionEvents(v: unknown) {
  const r = object(v)
  return {
    items: array(
      r.items,
      (value) => {
        const e = object(value),
          d = object(e.diagnostic)
        const parts = ['status', 'operation', 'probe', 'phase', 'code']
          .map((key) => nullable(d[key], string))
          .filter(Boolean)
        return { sequence: integer(e.sequence), description: parts.join(' · ') || '暂无可用诊断' }
      },
      100,
    ),
    nextCursor: nullable(r.nextCursor, string),
    hasMore: boolean(r.hasMore),
  }
}
export const terminal = (status: string) =>
  [
    'SOURCE_SAVED',
    'NO_CHANGES',
    'SUCCEEDED',
    'FAILED',
    'CANCELLED',
    'TIMED_OUT',
    'STOPPED',
    'NEEDS_RECONCILIATION',
  ].includes(status)
export function statusLabel(status: string) {
  return (
    (
      {
        QUEUED: '排队中',
        PREPARING: '准备中',
        RUNNING: '修改工程中',
        VALIDATING: '检查源码',
        ACCEPTING: '保存源码',
        SOURCE_SAVED: '源码已保存',
        NO_CHANGES: '无源码变化',
        INSTALLING: '安装依赖',
        RESOLVING: '准备依赖锁',
        CHECKING: '构建检查',
        PACKAGING: '打包工程',
        SUCCEEDED: '构建成功',
        STARTING: '启动中',
        READY: '应用运行中',
        STOPPING: '停止中',
        STOPPED: '已停止',
        FAILED: '失败',
        CANCELLED: '已取消',
        TIMED_OUT: '已超时',
        NEEDS_RECONCILIATION: '需要维护者核对',
        PREPARING_SOURCE: '初始化中',
        COMMITTED: '源码已保存',
      } as Record<string, string>
    )[status] ?? status
  )
}
