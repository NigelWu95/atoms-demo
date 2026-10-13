import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function prepare(page: Page) {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  const checks = page.getByRole('region', { name: '版本预览检查' })
  await expect(checks).toContainText('本次观察：检查通过')
  await checks.getByRole('button', { name: '保存检查结果' }).click()
  await expect(checks).toContainText('已保存报告：检查通过')
  await page.getByRole('button', { name: '发布应用 ↗' }).click()
  const panel = page.getByRole('region', { name: '应用发布' })
  await expect(panel).toContainText('当前版本 V1')
  await panel.getByRole('checkbox').check()
  return panel
}

test('publishes a local app, isolates visitors and preview data, takes offline and restores the same link', async ({
  page,
  browser,
}, testInfo) => {
  const panel = await prepare(page)
  const preview = page.frameLocator('iframe')
  await preview.getByLabel('新待办').fill('仅预览可见')
  await preview.getByRole('button', { name: '添加待办' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '确认发布当前版本' }).click()
  const link = panel.getByLabel('本地访问链接（尚未接入公网）')
  await expect(link).toHaveValue(/http:\/\/127\.0\.0\.1:5174\/a\/[a-f0-9]{32}$/)
  const url = await link.inputValue()
  await page.getByRole('button', { name: '发布应用 ↗' }).focus()
  await page.screenshot({ path: testInfo.outputPath('publication-panel.png'), fullPage: true })
  const first = await browser.newContext()
  const second = await browser.newContext()
  try {
    const visitor = await first.newPage()
    const errors: string[] = []
    const privateRequests: string[] = []
    visitor.on('pageerror', (error) => errors.push(error.message))
    visitor.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) errors.push(message.text())
    })
    visitor.on('request', (request) => {
      if (
        /^\/api\/(projects|runs|generation|health)(\/|\?|$)/.test(new URL(request.url()).pathname)
      )
        privateRequests.push(request.url())
    })
    await visitor.goto(url)
    const frame = visitor.frameLocator('iframe')
    await expect(visitor.getByText('应用已启动', { exact: true })).toBeVisible()
    await expect(visitor.locator('.sidebar')).toHaveCount(0)
    await expect(frame.getByText('仅预览可见', { exact: true })).toHaveCount(0)
    await frame.getByLabel('新待办').fill('访客甲的任务')
    await frame.getByRole('button', { name: '添加待办' }).click()
    await expect(visitor.getByText('已保存到数据库', { exact: true })).toBeVisible()
    await visitor.reload()
    await expect(frame.getByText('访客甲的任务', { exact: true })).toBeVisible()
    const other = await second.newPage()
    await other.goto(url)
    await expect(other.getByText('应用已启动', { exact: true })).toBeVisible()
    await expect(other.frameLocator('iframe').getByRole('checkbox')).toHaveCount(0)
    await expect(preview.getByText('访客甲的任务', { exact: true })).toHaveCount(0)
    await frame.getByLabel('新待办').fill('待保存输入')
    await visitor.screenshot({
      path: testInfo.outputPath('public-app-desktop.png'),
      fullPage: true,
    })
    await visitor.setViewportSize({ width: 390, height: 900 })
    await visitor.screenshot({ path: testInfo.outputPath('public-app-mobile.png'), fullPage: true })
    expect(errors).toEqual([])
    expect(privateRequests).toEqual([])
    await panel.getByRole('button', { name: '下线应用', exact: true }).click()
    await panel.getByRole('button', { name: '确认下线', exact: true }).click()
    await expect(panel.getByText('已下线', { exact: true })).toBeVisible()
    // A live tab must keep the input after the server refuses an offline write.
    await frame.getByRole('button', { name: '添加待办' }).click()
    await expect(visitor.getByText(/应用未发布或已下线/).first()).toBeVisible()
    await expect(frame.getByLabel('新待办')).toHaveValue('待保存输入')
    await other.reload()
    await expect(other.getByRole('alert')).toContainText('应用未发布或已下线')
    await expect(other.locator('iframe')).toHaveCount(0)
    await panel.getByRole('checkbox').check()
    await panel.getByRole('button', { name: '确认发布当前版本' }).click()
    await expect(link).toHaveValue(url)
    await visitor.getByRole('button', { name: '重新加载应用' }).click()
    await expect(frame.getByText('访客甲的任务', { exact: true })).toBeVisible()
    await expect(frame.getByText('待保存输入', { exact: true })).toHaveCount(0)
  } finally {
    await first.close()
    await second.close()
  }
})

// Keep each responsive audit independent of the full publish/offline lifecycle.
// Eight axe scans and that lifecycle together exhausted the 30s test budget;
// all widths, rules and the original assertion timeout remain unchanged.
for (const width of [320, 768, 1024, 1440]) {
  test(`publication panel and populated public app remain accessible at ${width}px`, async ({
    page,
    browser,
  }) => {
    const panel = await prepare(page)
    const preview = page.frameLocator('iframe')
    await preview.getByLabel('新待办').fill('发布面板检查')
    await preview.getByRole('button', { name: '添加待办' }).click()
    await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
    await panel.getByRole('button', { name: '确认发布当前版本' }).click()
    const link = panel.getByLabel('本地访问链接（尚未接入公网）')
    await expect(link).toHaveValue(/http:\/\/127\.0\.0\.1:5174\/a\/[a-f0-9]{32}$/)
    const url = await link.inputValue()
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    const context = await browser.newContext({ viewport: { width, height: 900 } })
    try {
      const visitor = await context.newPage()
      await visitor.goto(url)
      await expect(visitor.getByText('应用已启动', { exact: true })).toBeVisible()
      const frame = visitor.frameLocator('iframe')
      await frame.getByLabel('新待办').fill('公开应用检查')
      await frame.getByRole('button', { name: '添加待办' }).click()
      await expect(visitor.getByText('已保存到数据库', { exact: true })).toBeVisible()
      expect(await visitor.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      )
      expect(
        (
          await new AxeBuilder({ page: visitor })
            .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
            .analyze()
        ).violations,
      ).toEqual([])
    } finally {
      await context.close()
    }
  })
}

test('unknown publish and downline responses reuse original intents without duplicating revisions', async ({
  page,
}) => {
  const panel = await prepare(page)
  const writes: { method: string; url: string; body: string | null; key: string }[] = []
  await page.route('**/publication*', async (route) => {
    const request = route.request()
    if (request.method() === 'GET') return route.continue()
    const response = await route.fetch()
    writes.push({
      method: request.method(),
      url: request.url(),
      body: request.postData(),
      key: request.headers()['idempotency-key']!,
    })
    if (writes.filter((entry) => entry.method === request.method()).length === 1)
      await route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
    else await route.fulfill({ response })
  })
  await panel.getByRole('button', { name: '确认发布当前版本' }).click()
  await expect(panel).toContainText('上次发布结果未确认')
  await panel.getByRole('button', { name: '重试原发布操作' }).click()
  await expect(panel.getByText('已发布', { exact: true })).toBeVisible()
  expect(writes[0]).toEqual(writes[1])
  await panel.getByRole('button', { name: '下线应用', exact: true }).click()
  await panel.getByRole('button', { name: '确认下线', exact: true }).click()
  await expect(panel).toContainText('上次下线结果未确认')
  await panel.getByRole('button', { name: '重试原发布操作' }).click()
  await expect(panel.getByText('已下线', { exact: true })).toBeVisible()
  expect(writes[2]).toEqual(writes[3])
  const response = await page.request.get(
    page.url().replace('5174', '8081').replace('/projects/', '/api/projects/') + '/publication',
  )
  expect(response.status()).toBe(200)
  expect((await response.json()).revision).toBe(2)
})
