import { lstatSync, readFileSync } from 'node:fs'
import { isAbsolute } from 'node:path'
import { test as base, expect, type Page } from '@playwright/test'
import { startVerifiedRuntime, verifiedProject } from '../../scripts/serve-standalone.mjs'
import { createDatabaseGate, databaseEvidence } from '../../scripts/standalone-database.mjs'

// Explicit, local verification output only. No credentials or browser storage are serialized.
export function readEvidence(path = process.env.STANDALONE_VERIFICATION_REPORT) {
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

type Runtime = {
  restart(): Promise<void>
  withBuild(report: string, verify: () => Promise<void>): Promise<void>
  disconnectDatabase(): void
  reconnectDatabase(): void
  evidence(guest: string): ReturnType<typeof databaseEvidence>
}

// https://playwright.dev/docs/test-fixtures#worker-scoped-fixtures
// A worker owns its child handles. There is no port lookup, process reuse or JAR control endpoint.
export const test = base.extend<
  {
    loadedRuntimeId: string
    secondaryBaseURL: 'http://127.0.0.1:18090' | undefined
    networkBoundary: void
  },
  { runtime: Runtime }
>({
  loadedRuntimeId: [readEvidence().loadedRuntimeId, { option: true }],
  secondaryBaseURL: [undefined, { option: true }],
  runtime: [
    async ({}, use, info) => {
      if (info.config.workers !== 1 || info.config.fullyParallel)
        throw new Error('Standalone acceptance requires one sequential worker.')
      const gate = await createDatabaseGate(process.env.STANDALONE_RUNTIME_CONFIG)
      const start = () =>
        startVerifiedRuntime(process.env.STANDALONE_VERIFICATION_REPORT, gate.configPath)
      try {
        let owned = await start()
        let switching: Promise<void> | undefined
        try {
          await use({
            restart: async () => {
              await owned.stop()
              owned = await start()
            },
            withBuild: async (report, verify) => {
              if (switching) throw new Error('Only one owned build transition is allowed.')
              switching = (async () => {
                if (readEvidence(report).baseURL !== readEvidence().baseURL)
                  throw new Error('Upgrade must keep the original listener and configuration.')
                await verifiedProject(report) // Validate before stopping the old child.
                await owned.stop()
                try {
                  owned = await startVerifiedRuntime(report, gate.configPath)
                  await verify()
                } finally {
                  await owned.stop()
                  owned = await start()
                }
              })()
              try {
                await switching
              } finally {
                switching = undefined
              }
            },
            disconnectDatabase: gate.disconnect,
            reconnectDatabase: gate.reconnect,
            evidence: (guest) => databaseEvidence(process.env.STANDALONE_RUNTIME_CONFIG, guest),
          })
        } finally {
          // A test can abort during an async route callback; do not remove the private
          // database gate/config while an in-flight transition is restoring its child.
          try {
            await switching
          } finally {
            await owned.stop()
          }
        }
      } finally {
        await gate.close()
      }
    },
    { scope: 'worker', auto: true, timeout: 120000 },
  ],
  networkBoundary: [
    async ({ context, baseURL, secondaryBaseURL }, use, info) => {
      const blocked: string[] = []
      const responses: { method: string; path: string; status: number }[] = []
      // Only the isolation test opts into B. All other origins, including the platform, stay blocked.
      await context.route(
        (url) => url.origin !== baseURL && url.origin !== secondaryBaseURL,
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

export async function ready(page: Page, baseURL = readEvidence().baseURL) {
  let userId: unknown
  // Capture the real upstream response while forwarding it unchanged. Chrome can discard a
  // previous document's Network.getResponseBody handle across restart/navigation.
  // https://playwright.dev/docs/api/class-route#route-fetch
  await page.route(
    baseURL + '/api/session',
    async (route) => {
      const response = await route.fetch({ maxRetries: 0, maxRedirects: 0 })
      expect(response.status()).toBe(200)
      const session = await response.json()
      expect(session.isGuest).toBe(true)
      userId = session.userId
      await route.fulfill({ response })
    },
    { times: 1 },
  )
  await page.goto(baseURL + '/')
  const frame = page.frameLocator('iframe')
  await expect(frame.locator('#status')).toHaveText('数据已读取。')
  await expect(frame.getByRole('button', { name: '添加待办', exact: true })).toBeEnabled()
  if (
    typeof userId !== 'string' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(userId)
  )
    throw new Error('Expected a real standalone visitor identity.')
  return userId
}

export async function add(page: Page, title: string) {
  const frame = page.frameLocator('iframe')
  await frame.getByLabel('新待办', { exact: true }).fill(title)
  await frame.getByRole('button', { name: '添加待办', exact: true }).click()
}

export async function saved(page: Page) {
  await expect(page.getByRole('status').filter({ hasText: '已保存到数据库' })).toBeVisible()
  await expect(page.frameLocator('iframe').locator('#status')).toHaveText(
    '已保存。刷新后数据仍在。',
  )
}

export async function state(page: Page, baseURL = readEvidence().baseURL) {
  const response = await page.context().request.get(baseURL + '/api/state')
  expect(response.status()).toBe(200)
  return response.json()
}
