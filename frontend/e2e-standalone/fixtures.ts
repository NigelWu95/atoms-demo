import { lstatSync, readFileSync } from 'node:fs'
import { isAbsolute } from 'node:path'
import { test as base, expect } from '@playwright/test'

// Explicit, local verification output only. No credentials or browser storage are serialized.
export function readEvidence() {
  const path = process.env.STANDALONE_VERIFICATION_REPORT
  if (!path || !isAbsolute(path)) throw new Error('Explicit verification report required.')
  const stat = lstatSync(path)
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 4096)
    throw new Error('Invalid verification report.')
  const report: unknown = JSON.parse(readFileSync(path, 'utf8'))
  if (
    !report ||
    typeof report !== 'object' ||
    !('baseURL' in report) ||
    typeof report.baseURL !== 'string' ||
    !/^http:\/\/127\.0\.0\.1:\d{4,5}$/.test(report.baseURL) ||
    !('loadedRuntimeId' in report) ||
    typeof report.loadedRuntimeId !== 'string' ||
    !/^[a-f0-9]{64}$/.test(report.loadedRuntimeId)
  )
    throw new Error('Invalid verification identity.')
  return { baseURL: report.baseURL, loadedRuntimeId: report.loadedRuntimeId }
}

export const test = base.extend<{ loadedRuntimeId: string }>({
  loadedRuntimeId: [readEvidence().loadedRuntimeId, { option: true }],
})
export { expect }
