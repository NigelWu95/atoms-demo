import { expect, it } from 'vitest'
import { diagnosePreview, matchesRepair, repairPrompt, type RepairProposal } from './previewRepair'

const proposal: RepairProposal = {
  ownerId: 'owner',
  projectId: 'p',
  baseVersionId: 'v',
  expectedProjectRevision: 2,
  versionNo: 2,
  diagnosis: 'RUNTIME',
}
const project = { id: 'p', ownerId: 'owner', currentVersionId: 'v', projectRevision: 2 }
it('classifies failures without copying error text, URLs or business data', () => {
  const observation = {
    status: 'FAILED' as const,
    checks: [] as ('SCRIPT_PARSE' | 'SDK_STARTUP')[],
    errors: ['private-secret https://private.invalid/?token=x'],
  }
  expect(diagnosePreview(observation)).toBe('SYNTAX')
  observation.checks = ['SCRIPT_PARSE']
  expect(diagnosePreview(observation)).toBe('STARTUP')
  observation.checks.push('SDK_STARTUP')
  expect(diagnosePreview(observation)).toBe('RUNTIME')
  expect(diagnosePreview({ ...observation, status: 'PASSED' })).toBeNull()
  expect(diagnosePreview({ ...observation, status: 'UNKNOWN' })).toBeNull()
  const prompt = repairPrompt(proposal, '点击保存后报错')
  expect(prompt).toContain('点击保存后报错')
  expect(prompt).toContain('保留已有功能')
  expect(prompt).not.toContain('private-secret')
})
it('binds consent to owner, project, current version and project revision', () => {
  expect(matchesRepair(proposal, project, 'owner')).toBe(true)
  for (const changed of [
    { ...project, id: 'other' },
    { ...project, ownerId: 'other' },
    { ...project, currentVersionId: 'new' },
    { ...project, projectRevision: 3 },
  ])
    expect(matchesRepair(proposal, changed, 'owner')).toBe(false)
  expect(matchesRepair(proposal, project, null)).toBe(false)
  expect(matchesRepair(proposal, project, 'other')).toBe(false)
})
