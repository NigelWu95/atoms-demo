import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test('repair requires consent, preserves the draft and retries only the original request', async ({
  page,
}, testInfo) => {
  // Browser-local faults and model responses; no paid model call or production data mutation.
  await page.route(/\/api\/projects\/[^/]+\/versions\/[^/?]+$/, async (route) => {
    const response = await route.fetch()
    const version = await response.json()
    version.artifact.files['main.js'] = 'const broken = ; // private-diagnostic-must-not-leak'
    await route.fulfill({ response, json: version })
  })
  await page.route('**/api/generation', (route) =>
    route.fulfill({
      json: {
        configured: true,
        workerEnabled: true,
        remainingRequests: 2,
        model: 'test-only',
      },
    }),
  )
  const submissions: { body: string; key: string }[] = []
  await page.route(/\/api\/projects\/[^/]+\/runs(?:\?.*)?$/, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    submissions.push({
      body: route.request().postData()!,
      key: route.request().headers()['idempotency-key']!,
    })
    if (submissions.length === 1)
      await route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
    else
      await route.fulfill({
        status: 202,
        json: { runId: '00000000-0000-4000-8000-000000000001', status: 'QUEUED' },
      })
  })
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-review')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  const draft = page.getByRole('textbox', { name: '继续完善你的需求' })
  await draft.fill('未提交的需求必须保留')
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '准备修复建议' }).click()
  const confirmation = page.getByRole('region', { name: '确认预览修复' })
  await expect(confirmation).toBeVisible()
  expect(submissions).toEqual([])
  const confirm = confirmation.getByRole('button', { name: '确认并请求修复' })
  await expect(confirm).toBeDisabled()
  await confirmation.getByRole('textbox').fill('点击添加按钮后无法使用')
  await expect(confirmation.getByLabel('将发送的修复需求')).toContainText('点击添加按钮后无法使用')
  await confirmation.getByRole('checkbox').check()
  await confirm.click()
  await expect(page.getByRole('button', { name: '重试原提交' })).toBeVisible()
  expect(submissions).toHaveLength(1)
  expect(submissions[0]!.body).not.toContain('private-diagnostic-must-not-leak')
  expect(JSON.parse(submissions[0]!.body)).toMatchObject({
    intent: 'MODIFY',
    expectedProjectRevision: 2,
  })
  await expect(draft).toHaveValue('未提交的需求必须保留')
  await page.reload()
  await expect(page.getByRole('button', { name: '重试原提交' })).toBeEnabled()
  await expect(draft).toHaveValue('未提交的需求必须保留')
  expect(submissions).toHaveLength(1)
  await page.getByRole('button', { name: '重试原提交' }).click()
  await expect(page.getByRole('button', { name: '重试原提交' })).toHaveCount(0)
  expect(submissions).toHaveLength(2)
  expect(submissions[0]).toEqual(submissions[1])
  await page.getByRole('button', { name: '准备修复建议' }).click()
  await expect(confirmation).toBeVisible()
  await page.getByLabel('代码版本').selectOption({ label: 'V1 · 历史只读' })
  await expect(confirmation).toHaveCount(0)
  await expect(page.getByRole('button', { name: '准备修复建议' })).toHaveCount(0)
  await page.getByLabel('代码版本').selectOption({ label: 'V2 · 当前版本' })
  await page.setViewportSize({ width: 320, height: 1000 })
  await page
    .getByRole('group', { name: '工作台区域' })
    .getByRole('button', { name: '预览', exact: true })
    .click()
  await page.getByRole('button', { name: '准备修复建议' }).click()
  await expect(confirmation).toBeVisible()
  await expect(confirmation.getByRole('heading')).toBeFocused()
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect(confirmation).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath(`preview-repair-${width}.png`),
      fullPage: true,
    })
  }
  await confirmation.getByRole('button', { name: '取消建议' }).click()
  await expect(confirmation).toHaveCount(0)
  await expect(draft).toHaveValue('未提交的需求必须保留')
  expect(submissions).toHaveLength(2)
})
