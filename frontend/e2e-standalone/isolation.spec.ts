import { startVerifiedRuntime } from '../../scripts/serve-standalone.mjs'
import { createDatabaseGate, databaseEvidence } from '../../scripts/standalone-database.mjs'
import { test, expect, ready, add, saved, state } from './fixtures'

const secondOrigin = 'http://127.0.0.1:18090'
test.use({ secondaryBaseURL: secondOrigin })

test('same-host instances keep visitors and state separate in one browser context', async ({
  page,
  context,
  runtime,
  baseURL,
  loadedRuntimeId,
}, info) => {
  test.setTimeout(120000)
  const config = process.env.STANDALONE_SECONDARY_RUNTIME_CONFIG
  const gate = await createDatabaseGate(config)
  const start = () =>
    startVerifiedRuntime(process.env.STANDALONE_VERIFICATION_REPORT, gate.configPath, {
      baseURL: secondOrigin,
    })
  const failures: string[] = []
  const origins = new Set<string>()
  context.on('weberror', (error) => failures.push(error.error().name))
  context.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) failures.push(message.type())
  })
  context.on('request', (request) => origins.add(new URL(request.url()).origin))
  try {
    let secondRuntime = await start()
    try {
      const visitorA = await ready(page)
      await add(page, '只属于实例 A')
      await saved(page)
      const beforeA = await state(page)

      // Deliberately share the cookie jar: separate browser contexts would hide a cookie-name bug.
      // https://playwright.dev/docs/api/class-browsercontext#browser-context-new-page
      const second = await context.newPage()
      const visitorB = await ready(second, secondOrigin)
      expect(visitorA === visitorB).toBe(false)
      expect(await state(second, secondOrigin)).toEqual({
        value: { todos: [] },
        revision: 0,
        stateSchemaVersion: 1,
      })
      const app = await context.request.get(secondOrigin + '/api/app')
      expect(app.status()).toBe(200)
      expect((await app.json()).loadedRuntimeId).toBe(loadedRuntimeId)
      await add(second, '只属于实例 B')
      await saved(second)
      const beforeB = await state(second, secondOrigin)

      // Alternating both visits catches B replacing A's host-scoped session cookie and vice versa.
      for (const [tab, origin, visitor, before] of [
        [page, baseURL, visitorA, beforeA],
        [second, secondOrigin, visitorB, beforeB],
        [page, baseURL, visitorA, beforeA],
      ] as const) {
        expect((await ready(tab, origin)) === visitor).toBe(true)
        expect(await state(tab, origin)).toEqual(before)
      }
      await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText('只属于实例 A')
      await expect(second.frameLocator('iframe').getByRole('listitem')).toHaveText('只属于实例 B')
      const one = { revision: 1, stateRows: 1, idempotencyRows: 1 }
      const none = { revision: 0, stateRows: 0, idempotencyRows: 0 }
      expect(await runtime.evidence(visitorA)).toEqual(one)
      expect(await runtime.evidence(visitorB)).toEqual(none)
      expect(await databaseEvidence(config, visitorB)).toEqual(one)
      expect(await databaseEvidence(config, visitorA)).toEqual(none)
      await page.screenshot({ path: info.outputPath('instance-a.png'), fullPage: true })
      await second.screenshot({ path: info.outputPath('instance-b.png'), fullPage: true })

      await secondRuntime.stop()
      await add(page, 'B 停止后 A 仍可保存')
      await saved(page)
      expect(await runtime.evidence(visitorA)).toEqual({
        revision: 2,
        stateRows: 1,
        idempotencyRows: 2,
      })
      secondRuntime = await start()
      expect((await ready(second, secondOrigin)) === visitorB).toBe(true)
      expect(await state(second, secondOrigin)).toEqual(beforeB)
      expect([...origins].sort()).toEqual([baseURL, secondOrigin].sort())
      expect(failures).toEqual([])
    } finally {
      await secondRuntime.stop()
    }
  } finally {
    await gate.close()
  }
})
