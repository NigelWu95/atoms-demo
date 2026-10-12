import type { Page } from '@playwright/test'
import { test, expect, ready, add, saved, state, readEvidence } from './fixtures'

function upgradeReport() {
  const report = process.env.STANDALONE_UPGRADE_REPORT
  if (!report) throw new Error('Explicit independently verified upgrade report required.')
  expect(readEvidence(report).loadedRuntimeId).not.toBe(readEvidence().loadedRuntimeId)
  return report
}

async function runningIdentity(page: Page) {
  const response = await page.request.get('/api/app')
  expect(response.status()).toBe(200)
  return (await response.json()).loadedRuntimeId
}

test('rebuilt source preserves the visitor and data but rejects new writes from the old page', async ({
  page,
  context,
  runtime,
  loadedRuntimeId,
}, info) => {
  // Two real starts, each bounded by the existing 120s readiness gate, plus browser assertions.
  test.setTimeout(270000)
  const errors: string[] = []
  context.on('weberror', (event) => errors.push(event.error().name))
  const report = upgradeReport()
  const guest = await ready(page)
  await add(page, '升级前保存')
  await saved(page)
  const before = await state(page)
  await expect(
    runtime.withBuild('relative-report', async () => {
      throw new Error('Invalid evidence must not run the callback.')
    }),
  ).rejects.toThrow('Explicit verification report required.')
  expect(await runningIdentity(page)).toBe(loadedRuntimeId)

  await runtime.withBuild(report, async () => {
    expect(await runningIdentity(page)).toBe(readEvidence(report).loadedRuntimeId)
    await expect(
      runtime.withBuild(report, async () => {
        throw new Error('Overlapping transitions must not run the callback.')
      }),
    ).rejects.toThrow('Only one owned build transition is allowed.')
    expect(await state(page)).toEqual(before)
    const oldFrame = page.frameLocator('iframe')
    await expect(oldFrame.getByRole('heading')).toHaveText('把想法，一件件完成。')
    const rejected: { runtime: string; status: number; code: string }[] = []
    await page.route('**/api/state', async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      const actual = await route.fetch({ maxRetries: 0, maxRedirects: 0 })
      rejected.push({
        runtime: route.request().postDataJSON().loadedRuntimeId,
        status: actual.status(),
        code: (await actual.json()).error?.code,
      })
      await route.fulfill({ response: actual })
    })
    await add(page, '旧页面未保存草稿')
    await expect(page.getByRole('alert')).toContainText('应用版本已更新')
    expect(rejected).toEqual([{ runtime: loadedRuntimeId, status: 409, code: 'STALE_APP_VERSION' }])
    await expect(oldFrame.getByLabel('新待办', { exact: true })).toHaveValue('旧页面未保存草稿')
    await expect(oldFrame.getByRole('button', { name: '添加待办', exact: true })).toBeDisabled()
    expect(await state(page)).toEqual(before)
    expect(await runtime.evidence(guest)).toEqual({ revision: 1, stateRows: 1, idempotencyRows: 1 })
    await page.screenshot({ path: info.outputPath('stale-page-draft.png'), fullPage: true })

    const current = await context.newPage()
    expect(await ready(current)).toBe(guest)
    const frame = current.frameLocator('iframe')
    await expect(frame.getByRole('heading')).toHaveText('把想法，一件件完成（升级版）。')
    await expect(frame.getByRole('listitem')).toHaveText('升级前保存')
    await add(current, '升级后继续保存')
    await saved(current)
    expect((await state(current)).revision).toBe(2)
    await expect(frame.getByRole('listitem')).toHaveText(['升级前保存', '升级后继续保存'])
    await current.screenshot({
      path: info.outputPath('upgraded-preserved-data.png'),
      fullPage: true,
    })
  })
  expect(await runningIdentity(page)).toBe(loadedRuntimeId)
  expect(await ready(page)).toBe(guest)
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText([
    '升级前保存',
    '升级后继续保存',
  ])
  expect(await runtime.evidence(guest)).toEqual({ revision: 2, stateRows: 1, idempotencyRows: 2 })
  expect(errors).toEqual([])
})

test('an old committed intent replays after upgrade without reverting newer data', async ({
  page,
  context,
  runtime,
  loadedRuntimeId,
}, info) => {
  test.setTimeout(270000)
  const errors: string[] = []
  context.on('weberror', (event) => errors.push(event.error().name))
  const report = upgradeReport()
  const guest = await ready(page)
  const requests: { key: string | undefined; body: string | null }[] = []
  const acknowledgements: { runtime: string; status: number; revision: number }[] = []
  await page.route('**/api/state', async (route) => {
    const request = route.request()
    if (request.method() !== 'PUT') return route.continue()
    requests.push({ key: request.headers()['idempotency-key'], body: request.postData() })
    // Commit to the real database first; damage only delivery of the first acknowledgement.
    const actual = await route.fetch({ maxRetries: 0, maxRedirects: 0 })
    acknowledgements.push({
      runtime: request.postDataJSON().loadedRuntimeId,
      status: actual.status(),
      revision: (await actual.json()).revision,
    })
    if (requests.length === 1) return route.abort('connectionreset')
    await route.fulfill({ response: actual })
  })
  await add(page, '升级前已提交但未确认')
  await expect(page.getByRole('alert')).toContainText('保存结果尚未确认')
  expect(requests).toHaveLength(1)
  const committed = await state(page)
  expect(committed.revision).toBe(1)
  expect(await runtime.evidence(guest)).toEqual({ revision: 1, stateRows: 1, idempotencyRows: 1 })

  // Exercise restoration even if the verification callback fails; this is test cleanup,
  // not a product promise to roll back schemas or production data.
  await expect(
    runtime.withBuild(report, async () => {
      expect(await runningIdentity(page)).toBe(readEvidence(report).loadedRuntimeId)
      expect(await state(page)).toEqual(committed)
      const current = await context.newPage()
      expect(await ready(current)).toBe(guest)
      await add(current, '新构建的新数据')
      await saved(current)
      const newer = await state(current)
      expect(newer.revision).toBe(2)
      const frame = page.frameLocator('iframe')
      await expect(frame.getByLabel('新待办', { exact: true })).toHaveValue('升级前已提交但未确认')
      expect(requests).toHaveLength(1)
      await frame.getByRole('button', { name: '重试保存', exact: true }).click()
      await saved(page)
      expect(requests).toHaveLength(2)
      expect(requests[0]!.key).toMatch(/^[a-f0-9-]{36}:[a-f0-9-]{36}$/)
      expect(requests[1]).toEqual(requests[0])
      expect(acknowledgements).toEqual([
        { runtime: loadedRuntimeId, status: 200, revision: 1 },
        { runtime: loadedRuntimeId, status: 200, revision: 1 },
      ])
      expect(await state(page)).toEqual(newer)
      expect(await runtime.evidence(guest)).toEqual({
        revision: 2,
        stateRows: 1,
        idempotencyRows: 2,
      })
      await current.screenshot({
        path: info.outputPath('newer-data-after-old-replay.png'),
        fullPage: true,
      })
      throw new Error('Reviewed callback failure for lifecycle cleanup.')
    }),
  ).rejects.toThrow('Reviewed callback failure for lifecycle cleanup.')
  expect(await runningIdentity(page)).toBe(loadedRuntimeId)
  expect(await ready(page)).toBe(guest)
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText([
    '升级前已提交但未确认',
    '新构建的新数据',
  ])
  expect(await runtime.evidence(guest)).toEqual({ revision: 2, stateRows: 1, idempotencyRows: 2 })
  expect(errors).toEqual([])
})
