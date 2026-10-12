import { test, expect, ready, add, saved, state } from './fixtures'

test('real database disconnection never reports success, preserves data, and recovers by explicit retry', async ({
  page,
  runtime,
  baseURL,
}, info) => {
  const guest = await ready(page)
  await add(page, '故障前已保存')
  await saved(page)
  const before = { revision: 1, stateRows: 1, idempotencyRows: 1 }
  expect(await runtime.evidence(guest)).toEqual(before)
  let disconnected = false
  await page.route('**/api/state', async (route) => {
    if (route.request().method() === 'PUT' && !disconnected) {
      disconnected = true
      // CSRF succeeded already; break the actual JDBC sockets before the real PUT reaches Java.
      runtime.disconnectDatabase()
    }
    await route.continue()
  })
  try {
    const rejected = page.waitForResponse(
      (response) => response.url().endsWith('/api/state') && response.request().method() === 'PUT',
    )
    await add(page, '断连时保留的输入')
    expect((await rejected).status()).toBe(503)
    await expect(page.getByRole('alert')).toContainText('保存结果尚未确认')
    await expect(page.getByRole('alert')).toContainText('应用服务暂不可用')
    await expect(page.getByRole('status').filter({ hasText: '已保存到数据库' })).toHaveCount(0)
    const frame = page.frameLocator('iframe')
    await expect(frame.getByLabel('新待办', { exact: true })).toHaveValue('断连时保留的输入')
    await expect(frame.getByRole('button', { name: '添加待办', exact: true })).toBeDisabled()
    expect(await runtime.evidence(guest)).toEqual(before)
    expect((await page.context().request.get(baseURL + '/api/health')).status()).toBe(503)
    await page.screenshot({ path: info.outputPath('database-disconnected.png'), fullPage: true })
  } finally {
    runtime.reconnectDatabase()
  }
  await expect
    .poll(async () => (await page.context().request.get(baseURL + '/api/health')).status())
    .toBe(200)
  await page.frameLocator('iframe').getByRole('button', { name: '重试保存', exact: true }).click()
  await saved(page)
  expect(await runtime.evidence(guest)).toEqual({ revision: 2, stateRows: 1, idempotencyRows: 2 })
  expect((await state(page)).value.todos.map((todo: { title: string }) => todo.title)).toEqual([
    '故障前已保存',
    '断连时保留的输入',
  ])
})
