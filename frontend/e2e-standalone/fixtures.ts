import { lstatSync, readFileSync } from 'node:fs'
import { isAbsolute } from 'node:path'
import { test as base, expect } from '@playwright/test'
import { startVerifiedRuntime } from '../../scripts/serve-standalone.mjs'

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

type Runtime = { restart(): Promise<void> }

// https://playwright.dev/docs/test-fixtures#worker-scoped-fixtures
// A worker owns its child handles. There is no port lookup, process reuse or JAR control endpoint.
export const test = base.extend<
  { loadedRuntimeId: string; networkBoundary: void },
  { runtime: Runtime }
>({
  loadedRuntimeId: [readEvidence().loadedRuntimeId, { option: true }],
  runtime: [
    async ({}, use, info) => {
      if (info.config.workers !== 1 || info.config.fullyParallel)
        throw new Error('Standalone acceptance requires one sequential worker.')
      const start = () =>
        startVerifiedRuntime(
          process.env.STANDALONE_VERIFICATION_REPORT,
          process.env.STANDALONE_RUNTIME_CONFIG,
        )
      let owned = await start()
      try {
        await use({
          restart: async () => {
            await owned.stop()
            owned = await start()
          },
        })
      } finally {
        await owned.stop()
      }
    },
    { scope: 'worker', auto: true, timeout: 120000 },
  ],
  networkBoundary: [
    async ({ context, baseURL }, use, info) => {
      const blocked: string[] = []
      const responses: { method: string; path: string; status: number }[] = []
      // Block every other origin, including platform endpoints. Never serialize headers or bodies.
      await context.route(
        (url) => url.origin !== baseURL,
        async (route) => {
          blocked.push(new URL(route.request().url()).origin)
          await route.abort('blockedbyclient')
        },
      )
      context.on('response', (response) => {
        const path = new URL(response.url()).pathname
        if (responses.length < 512)
          responses.push({
            method: response.request().method(),
            path: /^\/api\/[a-z/]+$/.test(path) ? path : '[asset]',
            status: response.status(),
          })
      })
      await use()
      if (info.status !== info.expectedStatus)
        await info.attach('redacted-network', {
          body: JSON.stringify({ blockedCount: blocked.length, responses }),
          contentType: 'application/json',
        })
      expect(blocked, 'No platform or external origin may be contacted').toEqual([])
      expect(context.serviceWorkers()).toHaveLength(0)
    },
    { auto: true },
  ],
})
export { expect }
