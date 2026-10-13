import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function openTodo(page: Page) {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  return page.getByRole('region', { name: '版本预览检查' })
}

// Faults are confined to this browser's responses. Reports still use the real API/database;
// no arbitrary-source write endpoint is added to the application or the test fixture.
async function replaceScript(page: Page, script: string) {
  await page.route(/\/api\/projects\/[^/]+\/versions\/[^/?]+$/, async (route) => {
    const response = await route.fetch()
    const version = await response.json()
    version.artifact.files['main.js'] = script
    await route.fulfill({ response, json: version })
  })
  await page.route(/\/api\/projects\/[^/]+\/versions\/[^/]+\/document\?/, async (route) => {
    const response = await route.fetch()
    const html = await response.text()
    const source = /^    const source = .*;$/m
    expect(html).toMatch(source)
    await route.fulfill({
      response,
      body: html.replace(
        source,
        () => `    const source = ${JSON.stringify({ css: '', js: script })};`,
      ),
    })
  })
}

test('saves a version report, replays an unknown response and restores it after reload', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  const checks = await openTodo(page)
  await expect(checks).toContainText('本次观察：检查通过')
  const intents: { key: string; body: string; reportId: string }[] = []
  await page.route('**/preview-reports', async (route) => {
    const request = route.request()
    expect(request.method()).toBe('POST')
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    const report = await response.json()
    intents.push({
      key: request.headers()['idempotency-key']!,
      body: request.postData()!,
      reportId: report.reportId,
    })
    if (intents.length === 1) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
    } else await route.fulfill({ response })
  })
  await checks.getByRole('button', { name: '保存检查结果' }).click()
  await expect(checks.getByText('上次保存结果未确认', { exact: false })).toBeVisible()
  await checks.getByRole('button', { name: '重试原检查报告' }).click()
  await expect(checks).toContainText('已保存报告：检查通过')
  expect(intents).toHaveLength(2)
  expect(intents[0]).toEqual(intents[1])
  expect(intents[0]!.key).toBeTruthy()
  expect(intents[0]!.reportId).toBeTruthy()
  await page.reload()
  await expect(checks).toContainText('本次观察：检查通过')
  await expect(checks).toContainText('已保存报告：检查通过')
  const input = page.frameLocator('iframe').getByLabel('新待办')
  await input.fill('检查报告浏览器验收')
  await expect(input).toHaveValue('检查报告浏览器验收')
  await page.screenshot({ path: testInfo.outputPath('preview-report-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.screenshot({ path: testInfo.outputPath('preview-report-mobile.png'), fullPage: true })
  expect(errors).toEqual([])
})

// Responsive scans have their own unchanged 30s budgets, independent of
// response-loss/reload testing. All original widths and axe rules are retained.
for (const width of [320, 768, 1024, 1440]) {
  test(`saved preview report remains accessible at ${width}px`, async ({ page }) => {
    const checks = await openTodo(page)
    await expect(checks).toContainText('本次观察：检查通过')
    await checks.getByRole('button', { name: '保存检查结果' }).click()
    await expect(checks).toContainText('已保存报告：检查通过')
    await page.reload()
    await expect(checks).toContainText('已保存报告：检查通过')
    await page.setViewportSize({ width, height: 900 })
    if (width === 320)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览', exact: true })
        .click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
  })
}

test('blocks malformed scripts before fetching a runtime document and persists the failure', async ({
  page,
}) => {
  await replaceScript(page, 'const broken = ;')
  const documents: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/document?')) documents.push(request.url())
  })
  const checks = await openTodo(page)
  await expect(checks).toContainText('本次观察：检查失败')
  await expect(checks).toContainText('语法检查未通过')
  await expect(page.locator('iframe')).toHaveCount(0)
  expect(documents).toEqual([])
  await checks.getByRole('button', { name: '保存检查结果' }).click()
  await expect(checks).toContainText('已保存报告：检查失败')
  await page.reload()
  await expect(checks).toContainText('已保存报告：检查失败')
})

for (const [name, script, message] of [
  [
    'SDK startup rejection',
    'Atoms.ready(async () => { throw new Error("受控启动错误"); });',
    '受控启动错误',
  ],
  [
    'uncaught runtime error',
    'Atoms.ready(() => { setTimeout(() => { throw new Error("受控运行错误"); }, 0); });',
    '受控运行错误',
  ],
  [
    'unhandled rejection',
    'Atoms.ready(() => { setTimeout(() => { Promise.reject(new Error("受控异步错误")); }, 0); });',
    '受控异步错误',
  ],
] as const) {
  test(`reports a real sandbox ${name} and persists the failure`, async ({ page }) => {
    await replaceScript(page, script)
    const checks = await openTodo(page)
    await expect(page.locator('iframe')).toHaveCount(1)
    await expect(checks).toContainText('本次观察：检查失败')
    await expect(checks).toContainText(message)
    await checks.getByRole('button', { name: '保存检查结果' }).click()
    await expect(checks).toContainText('已保存报告：检查失败')
  })
}
