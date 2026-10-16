import type { Observation } from '../api/previewReports'
import type { Project } from '../api/projects'

export type PreviewDiagnosis = 'SYNTAX' | 'STARTUP' | 'RUNTIME'
export type RepairProposal = {
  ownerId: string
  projectId: string
  baseVersionId: string
  expectedProjectRevision: number
  versionNo: number
  diagnosis: PreviewDiagnosis
}

// Only fixed categories cross into a prompt. Raw app errors can contain secrets or PII.
export function diagnosePreview(observation: Observation): PreviewDiagnosis | null {
  if (observation.status !== 'FAILED') return null
  if (!observation.checks.includes('SCRIPT_PARSE')) return 'SYNTAX'
  return observation.checks.includes('SDK_STARTUP') ? 'RUNTIME' : 'STARTUP'
}
export function matchesRepair(
  proposal: RepairProposal,
  project: Pick<Project, 'id' | 'ownerId' | 'currentVersionId' | 'projectRevision'>,
  ownerId: string | null,
) {
  return (
    ownerId === proposal.ownerId &&
    project.ownerId === ownerId &&
    project.id === proposal.projectId &&
    project.currentVersionId === proposal.baseVersionId &&
    project.projectRevision === proposal.expectedProjectRevision
  )
}
export function repairPrompt(proposal: RepairProposal, description: string): string {
  const diagnosis: Record<PreviewDiagnosis, string> = {
    SYNTAX: 'main.js 未通过脚本语法检查。',
    STARTUP: '预览启动阶段未完成或发生错误；也可能是网络或运行环境问题，不能直接认定为代码错误。',
    RUNTIME: '应用启动后观察到运行错误。',
  }
  return [
    `请基于当前 V${proposal.versionNo} 修复共享预览问题，保留已有功能、数据结构和持久化行为。`,
    `观察类别：${diagnosis[proposal.diagnosis]}`,
    ...(description.trim() ? [`用户补充：${description.trim()}`] : []),
    '未附带原始报错、堆栈或业务数据。请检查当前源码，仅修改必要部分；不要把生成完成当作功能验收。',
  ].join('\n')
}
