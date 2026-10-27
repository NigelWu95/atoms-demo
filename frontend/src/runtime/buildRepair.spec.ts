import { expect, it } from 'vitest'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'
import {
  buildRepairBlock,
  buildRepairSelection,
  buildRepairProposal,
  buildRepairPrompt,
  matchesBuildRepair,
  type BuildRepairContext,
} from './buildRepair'

const context: BuildRepairContext = {
  ownerId: 'owner',
  projectId: 'project',
  head: { projectId: 'project', sourceVersionId: 'v2', revision: 2, status: 'READY' },
}
const build: ExecutionTask = {
  id: 'build',
  kind: 'BUILD',
  sourceVersionId: 'v2',
  status: 'FAILED',
  buildId: null,
  resultVersionId: null,
  errorCode: 'EXECUTION_CHECK_FAILED',
  executionPending: false,
  finishedAt: '2026-10-27T00:00:00Z',
  cancelRequested: false,
  stopRequested: false,
}
it('includes only fixed diagnostic categories even when extra event fields contain private text', () => {
  const proposal = buildRepairProposal(
    buildRepairSelection(context, build)!,
    [
      {
        sequence: 1,
        description: 'secret /private/config PASSWORD=hidden',
        execution: { operation: 'TYPECHECK', phase: 'failed' },
      },
    ],
    false,
  )
  const prompt = buildRepairPrompt(proposal, '保留已有数据')
  expect(prompt).toContain('前端类型检查')
  expect(prompt).toContain('保留已有数据')
  expect(prompt).toContain('revision 2')
  expect(prompt).not.toMatch(/secret|private|PASSWORD|hidden/)
  const incomplete = buildRepairProposal(
    proposal.selection,
    [
      {
        sequence: 1,
        description: 'ignored',
        execution: { operation: 'TYPECHECK', phase: 'failed' },
      },
    ],
    true,
  )
  expect(incomplete.summary).not.toContain('前端类型检查')
})
it.each([
  'EXECUTION_TIMEOUT',
  'EXECUTION_NPM_APPROVAL_REQUIRED',
  'EXECUTION_DB_UNAVAILABLE',
  'EXECUTION_DIAGNOSTIC_UNAVAILABLE',
  'EXECUTION_CUSTOM_SECRET',
  '__proto__',
])('does not turn %s into a model repair request', (errorCode) => {
  expect(buildRepairSelection(context, { ...build, errorCode })).toBeNull()
})
it('binds repair consent to owner, project, source revision and the finished build', () => {
  const proposal = buildRepairProposal(buildRepairSelection(context, build)!, [], false)
  expect(matchesBuildRepair(proposal, context, build)).toBe(true)
  for (const changed of [
    null,
    { ...context, ownerId: 'other' },
    { ...context, projectId: 'other' },
    { ...context, head: { ...context.head, revision: 3 } },
  ])
    expect(matchesBuildRepair(proposal, changed, build)).toBe(false)
  for (const changed of [
    { ...build, id: 'other' },
    { ...build, sourceVersionId: 'v1' },
    { ...build, executionPending: true },
    { ...build, finishedAt: null },
    { ...build, status: 'NEEDS_RECONCILIATION' },
  ])
    expect(matchesBuildRepair(proposal, context, changed)).toBe(false)
  expect(
    buildRepairBlock({ ...context, head: { ...context.head, sourceVersionId: 'v3' } }, build),
  ).toContain('历史源码')
})
