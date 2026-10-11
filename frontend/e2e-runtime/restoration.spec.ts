import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function openHistory(page: Page) {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-history')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
}
async function confirmRestore(page: Page) {
  await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V1 · 历史只读' })
  await expect(page.getByText('历史只读 · 使用初始数据')).toBeVisible()
  await page.getByRole('button', { name: '恢复此版本', exact: true }).click()
  const panel = page.getByRole('region', { name: '代码版本恢复' })
  await expect(panel).toContainText('不回滚业务数据，不自动发布')
  await expect(panel.getByRole('button', { name: '确认恢复为新版本' })).toBeDisabled()
  await panel.getByRole('checkbox', { name: '我确认只恢复代码' }).check()
  return panel
}
test('restores V1 as V3 while retaining application data, V2 history and the published version', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  await openHistory(page)
  const frame = page.frameLocator('iframe')
  await frame.getByLabel('新待办').fill('恢复代码后保留的数据')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  const checks = page.getByRole('region', { name: '版本预览检查' })
  await expect(checks).toContainText('本次观察：检查通过')
  await checks.getByRole('button', { name: '保存检查结果' }).click()
  await expect(checks).toContainText('已保存报告：检查通过')
  await page.getByRole('button', { name: '发布应用 ↗' }).click()
  const publication = page.getByRole('region', { name: '应用发布' })
  await publication.getByRole('checkbox').check()
  await publication.getByRole('button', { name: '确认发布当前版本' }).click()
  await expect(publication.getByText('已发布', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '发布应用 ↗' }).click()
  const panel = await confirmRestore(page)
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    if (width < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览', exact: true })
        .click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
  }
  await page.screenshot({ path: testInfo.outputPath('restore-confirmation.png'), fullPage: true })
  await panel.getByRole('button', { name: '确认恢复为新版本' }).click()
  await expect(panel).toContainText('已恢复为 V3')
  await expect(
    page.getByLabel('代码版本', { exact: true }).locator('option:checked'),
  ).toContainText('V3 · 当前版本')
  await expect(frame.getByText('恢复代码后保留的数据', { exact: true })).toBeVisible()
  await expect(checks).toContainText('已保存报告：尚未完成')
  await page.getByRole('button', { name: '发布应用 ↗' }).click()
  await expect(publication).toContainText('当前版本 V3 · 上次发布 V2')
  await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V2 · 历史只读' })
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  await page.reload()
  await expect(
    page.getByLabel('代码版本', { exact: true }).locator('option:checked'),
  ).toContainText('V3 · 当前版本')
  await expect(frame.getByText('恢复代码后保留的数据', { exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('lost restore response retries the same intent and creates only V3', async ({ page }) => {
  await openHistory(page)
  const requests: Array<{ body: string | null; key: string }> = []
  await page.route('**/restorations', async (route) => {
    const request = route.request()
    requests.push({ body: request.postData(), key: request.headers()['idempotency-key']! })
    const response = await route.fetch()
    if (requests.length === 1)
      await route.fulfill({ status: 201, contentType: 'application/json', body: 'invalid-json' })
    else await route.fulfill({ response })
  })
  const panel = await confirmRestore(page)
  await panel.getByRole('button', { name: '确认恢复为新版本' }).click()
  await expect(panel.getByRole('button', { name: '重试原恢复操作' })).toBeVisible()
  await expect(page.getByLabel('代码版本', { exact: true })).toBeDisabled()
  await panel.getByRole('button', { name: '重试原恢复操作' }).click()
  await expect(panel).toContainText('已恢复为 V3')
  expect(requests).toHaveLength(2)
  expect(requests[0]).toEqual(requests[1])
  await expect(page.getByLabel('代码版本', { exact: true }).locator('option')).toHaveCount(3)
})
