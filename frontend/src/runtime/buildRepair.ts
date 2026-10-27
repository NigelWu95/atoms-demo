import type { SourceHead } from '../api/projectWorkbench'
import type { ExecutionTask, TaskEvent } from '../api/projectWorkbenchTasks'

export type BuildRepairContext = { ownerId: string; projectId: string; head: SourceHead }
export type BuildRepairSelection = {
  ownerId: string
  projectId: string
  sourceVersionId: string
  revision: number
  buildId: string
  diagnosis: string
}
export type BuildRepairProposal = { selection: BuildRepairSelection; summary: string }

const diagnoses: Record<string, string> = {
  EXECUTION_CHECK_FAILED: '源码检查未通过',
  EXECUTION_PROCESS_FAILED: '构建步骤执行失败，具体原因仍需核对源码与环境',
  EXECUTION_REPORT_INVALID: '测试结果不满足构建要求',
  EXECUTION_OUTPUT_NOT_CLEAN: '构建输出不符合要求',
  EXECUTION_PACKAGING_INVALID: '工程打包结果不符合要求',
}
const operations: Record<string, string> = {
  TYPECHECK: '前端类型检查',
  LINT: '代码规范检查',
  UNIT_TEST: '前端单元测试',
  FRONTEND_BUILD: '前端构建',
  MAVEN_PACKAGE: 'Java 测试或打包',
}

export function buildRepairBlock(context: BuildRepairContext | null, build: ExecutionTask | null) {
  if (!context?.head.sourceVersionId || context.head.status !== 'READY')
    return '请先等待当前源码就绪。'
  if (!build || build.kind !== 'BUILD' || build.status !== 'FAILED') return '请选择已失败的构建。'
  if (!build.finishedAt || build.executionPending) return '执行尚未确认结束，请先核对构建状态。'
  if (build.sourceVersionId !== context.head.sourceVersionId)
    return '该失败构建对应历史源码。请先构建当前源码，避免依据旧问题修改新版本。'
  if (!build.errorCode || !Object.hasOwn(diagnoses, build.errorCode))
    return '此故障需要先核对环境、依赖策略、超时或执行状态；暂不生成源码修复建议。'
  return null
}
export function buildRepairSelection(
  context: BuildRepairContext | null,
  build: ExecutionTask | null,
): BuildRepairSelection | null {
  if (buildRepairBlock(context, build) || !context || !build) return null
  return {
    ownerId: context.ownerId,
    projectId: context.projectId,
    sourceVersionId: context.head.sourceVersionId!,
    revision: context.head.revision,
    buildId: build.id,
    diagnosis: diagnoses[build.errorCode!]!,
  }
}
export function matchesBuildRepair(
  proposal: BuildRepairProposal,
  context: BuildRepairContext | null,
  build: ExecutionTask | null,
) {
  const current = buildRepairSelection(context, build),
    original = proposal.selection
  return (
    !!current &&
    current.ownerId === original.ownerId &&
    current.projectId === original.projectId &&
    current.sourceVersionId === original.sourceVersionId &&
    current.revision === original.revision &&
    current.buildId === original.buildId &&
    current.diagnosis === original.diagnosis
  )
}
export function buildRepairProposal(
  selection: BuildRepairSelection,
  events: TaskEvent[],
  incomplete: boolean,
): BuildRepairProposal {
  // Only platform-owned category text enters the prompt, never event descriptions or raw reports.
  const failed = events
    .filter(
      (e) =>
        e.execution?.phase === 'failed' &&
        e.execution.operation &&
        Object.hasOwn(operations, e.execution.operation),
    )
    .at(-1)
  const step =
    !incomplete && failed?.execution?.operation ? operations[failed.execution.operation] : null
  return {
    selection,
    summary: `${selection.diagnosis}。${step ? `失败环节：${step}。` : '当前摘要未能确定具体失败环节，请从源码和现有测试定位。'}`,
  }
}
export function buildRepairPrompt(proposal: BuildRepairProposal, description: string) {
  const { sourceVersionId, revision, buildId } = proposal.selection
  return [
    '请基于当前完整工程修复构建失败，保留已有功能、接口和数据结构。',
    `修复基线：源码 ${sourceVersionId}，revision ${revision}；失败构建 ${buildId}。`,
    `检查摘要：${proposal.summary}`,
    ...(description.trim() ? [`用户补充：${description.trim()}`] : []),
    '请读取相关源码和测试，仅修改必要部分；不要降低检查标准、删除测试或修改已应用的数据库迁移。',
    '未附带原始日志、堆栈、命令或业务数据。依赖安装、测试与构建由平台另行执行；源码保存不代表修复已通过验收。',
  ].join('\n')
}
