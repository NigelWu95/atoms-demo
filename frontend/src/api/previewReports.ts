import { api, isRecord, protocolFailure } from './client'

export type Check = 'SCRIPT_PARSE' | 'SDK_STARTUP' | 'RUNTIME_ERRORS'
export type Observation = {
  status: 'UNKNOWN' | 'PASSED' | 'FAILED'
  checks: Check[]
  errors: string[]
}
export type PreviewReport = Observation & {
  status: 'PASSED' | 'FAILED'
  viewport: { width: number; height: number }
}
export type SavedReport = Observation & {
  reportId: string | null
  viewport: PreviewReport['viewport'] | null
  createdAt: string | null
}
const path = (project: string, version: string) =>
  `/api/projects/${encodeURIComponent(project)}/versions/${encodeURIComponent(version)}/preview-reports`
export function getPreviewReport(project: string, version: string) {
  return api.request(path(project, version) + '/latest', (value): SavedReport => {
    if (
      !isRecord(value) ||
      !['UNKNOWN', 'PASSED', 'FAILED'].includes(String(value.status)) ||
      !Array.isArray(value.checks) ||
      !value.checks.every((item) =>
        ['SCRIPT_PARSE', 'SDK_STARTUP', 'RUNTIME_ERRORS'].includes(item),
      ) ||
      !Array.isArray(value.errors) ||
      value.errors.length > 5 ||
      !value.errors.every(
        (item) =>
          typeof item === 'string' &&
          item.trim().length > 0 &&
          item.length <= 1000 &&
          !item.includes('\0'),
      )
    )
      return protocolFailure()
    if (value.status === 'UNKNOWN') {
      if (
        value.reportId !== null ||
        value.createdAt !== null ||
        value.viewport !== null ||
        value.checks.length ||
        value.errors.length
      )
        return protocolFailure()
      return {
        status: 'UNKNOWN',
        reportId: null,
        checks: [],
        errors: [],
        viewport: null,
        createdAt: null,
      }
    }
    const viewport = value.viewport
    if (
      typeof value.reportId !== 'string' ||
      !value.reportId ||
      typeof value.createdAt !== 'string' ||
      !Number.isFinite(Date.parse(value.createdAt)) ||
      !isRecord(viewport) ||
      typeof viewport.width !== 'number' ||
      !Number.isInteger(viewport.width) ||
      typeof viewport.height !== 'number' ||
      !Number.isInteger(viewport.height) ||
      (value.status !== 'PASSED' && value.status !== 'FAILED')
    )
      return protocolFailure()
    if (
      viewport.width < 1 ||
      viewport.width > 10000 ||
      viewport.height < 1 ||
      viewport.height > 10000 ||
      new Set(value.checks).size !== value.checks.length ||
      (value.status === 'PASSED' && (value.checks.length !== 3 || value.errors.length)) ||
      (value.status === 'FAILED' && !value.errors.length)
    )
      return protocolFailure()
    return {
      status: value.status,
      reportId: value.reportId,
      checks: value.checks,
      errors: value.errors,
      viewport: { width: viewport.width, height: viewport.height },
      createdAt: value.createdAt,
    }
  })
}
export function savePreviewReport(
  project: string,
  version: string,
  report: PreviewReport,
  key: string,
) {
  return api.request(
    path(project, version),
    (value): { reportId: string; status: PreviewReport['status'] } => {
      if (
        !isRecord(value) ||
        typeof value.reportId !== 'string' ||
        !value.reportId ||
        value.status !== report.status
      )
        return protocolFailure()
      return { reportId: value.reportId, status: report.status }
    },
    { method: 'POST', body: report, idempotencyKey: key },
  )
}
