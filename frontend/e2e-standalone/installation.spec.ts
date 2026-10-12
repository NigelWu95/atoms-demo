import { startVerifiedRuntime } from '../../scripts/serve-standalone.mjs'
import {
  createDatabaseGate,
  databaseEvidence,
  withMismatchedInstallation,
} from '../../scripts/standalone-database.mjs'
import { test, expect, ready, add, saved, state } from './fixtures'

const secondOrigin = 'http://127.0.0.1:18090'
test.use({ secondaryBaseURL: secondOrigin })

test('final JAR rejects a wrong installation ID without replacing stored identity or data', async ({
  page,
}) => {
  test.setTimeout(180000)
  const config = process.env.STANDALONE_SECONDARY_RUNTIME_CONFIG
  const gate = await createDatabaseGate(config)
  const start = (runtimeConfig = gate.configPath) =>
    startVerifiedRuntime(process.env.STANDALONE_VERIFICATION_REPORT, runtimeConfig, {
      baseURL: secondOrigin,
    })
  try {
    let owned = await start()
    try {
      const visitor = await ready(page, secondOrigin)
      await add(page, '拒绝误配后仍可恢复')
      await saved(page)
      const before = await state(page, secondOrigin)
      const counts = { revision: 1, stateRows: 1, idempotencyRows: 1 }
      expect(await databaseEvidence(config, visitor)).toEqual(counts)
      await owned.stop()

      await withMismatchedInstallation(gate.configPath, async (wrongConfig) => {
        // Even an unexpectedly accepted process must be stopped before the assertion fails.
        await expect(start(wrongConfig).then((unexpected) => unexpected.stop())).rejects.toThrow(
          'Owned standalone process exited before readiness.',
        )
      })
      // This read-only check also verifies the original database installation marker still matches.
      expect(await databaseEvidence(config, visitor)).toEqual(counts)
      owned = await start()
      expect((await ready(page, secondOrigin)) === visitor).toBe(true)
      expect(await state(page, secondOrigin)).toEqual(before)
      await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText(
        '拒绝误配后仍可恢复',
      )
      await add(page, '正确配置下可以继续保存')
      await saved(page)
      expect(await databaseEvidence(config, visitor)).toEqual({
        revision: 2,
        stateRows: 1,
        idempotencyRows: 2,
      })
    } finally {
      await owned.stop()
    }
  } finally {
    await gate.close()
  }
})
