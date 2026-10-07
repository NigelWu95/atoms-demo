import { afterEach, expect, it, vi } from 'vitest'
import { api } from './client'
import { getPreviewReport, savePreviewReport, type PreviewReport } from './previewReports'

const report: PreviewReport = {
  status: 'PASSED',
  checks: ['SCRIPT_PARSE', 'SDK_STARTUP', 'RUNTIME_ERRORS'],
  errors: [],
  viewport: { width: 1440, height: 900 },
}
afterEach(() => vi.unstubAllGlobals())
it('uses the caller intent and version, and reads a persisted bounded report', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ reportId: 'r1', status: 'PASSED' }))
    .mockResolvedValueOnce(
      Response.json({ ...report, reportId: 'r1', createdAt: '2026-10-07T00:00:00Z' }),
    )
  vi.stubGlobal('fetch', fetch)
  api.setCsrf({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' })
  await savePreviewReport('p1', 'v1', report, 'original-intent')
  expect(fetch.mock.calls[0]![0]).toBe('/api/projects/p1/versions/v1/preview-reports')
  expect(fetch.mock.calls[0]![1].headers['Idempotency-Key']).toBe('original-intent')
  expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual(report)
  expect((await getPreviewReport('p1', 'v1')).status).toBe('PASSED')
})
it.each([
  { status: 'UNKNOWN', reportId: 'unexpected' },
  { checks: ['SCRIPT_PARSE'] },
  { checks: ['SCRIPT_PARSE', 'SCRIPT_PARSE', 'RUNTIME_ERRORS'] },
  { errors: ['unexpected'] },
  { viewport: { width: 0, height: 900 } },
  { status: 'FAILED', errors: [' '] },
])('rejects inconsistent report responses: %j', async (invalid) => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        Response.json({ ...report, reportId: 'r1', createdAt: '2026-10-07T00:00:00Z', ...invalid }),
      ),
  )
  await expect(getPreviewReport('p1', 'v1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
