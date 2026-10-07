import { expect, test } from '@playwright/test'

test('packaged site supports secure sessions, persisted projects and deep-link refresh', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('服务器发布包验收，不调用模型')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  await page.reload()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('服务器发布包验收')
  expect(errors).toEqual([])
})

test('packaged preview, path publication and isolated visitor state work through nginx TLS', async ({
  page,
  browser,
}, testInfo) => {
  // Fixed test artifact enters only through the test-only 8081 service; not the JAR.
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  const link = page.getByRole('link', { name: '打开测试工作台' })
  await expect(link).toBeVisible()
  const projectPath = new URL((await link.getAttribute('href'))!).pathname
  expect(projectPath).toMatch(/^\/projects\/[0-9a-f-]+$/)
  await page.goto(projectPath)
  const checks = page.getByRole('region', { name: '版本预览检查' })
  await expect(checks).toContainText('本次观察：检查通过')
  await checks.getByRole('button', { name: '保存检查结果' }).click()
  await expect(checks).toContainText('已保存报告：检查通过')
  await page.getByRole('button', { name: '发布应用 ↗' }).click()
  const panel = page.getByRole('region', { name: '应用发布' })
  await expect(panel).toContainText('私有对话和预览数据不会公开')
  await panel.getByRole('checkbox').check()
  await panel.getByRole('button', { name: '确认发布当前版本' }).click()
  const url = panel.getByLabel('应用访问链接', { exact: true })
  await expect(url).toHaveValue(/^https:\/\/127\.0\.0\.1:18443\/a\/[a-f0-9]{32}$/)
  const publicUrl = await url.inputValue()
  const visitor = await browser.newContext({ ignoreHTTPSErrors: true })
  const other = await browser.newContext({ ignoreHTTPSErrors: true })
  try {
    const tab = await visitor.newPage()
    const errors: string[] = []
    tab.on('pageerror', (error) => errors.push(error.message))
    tab.on('console', (message) => {
      if (['warning', 'error'].includes(message.type())) errors.push(message.text())
    })
    const documentReply = tab.waitForResponse(/\/api\/public\/apps\/[a-f0-9]+\/document/)
    await tab.goto(publicUrl)
    const reply = await documentReply
    const headers = await reply.headersArray()
    expect(
      headers.filter((header) => header.name.toLowerCase() === 'content-security-policy'),
    ).toHaveLength(1)
    expect(reply.headers()['x-frame-options']).toBe('SAMEORIGIN')
    expect(reply.headers()['content-security-policy']).toContain('sandbox allow-scripts')
    const frame = tab.frameLocator('iframe')
    await expect(tab.getByText('应用已启动', { exact: true })).toBeVisible()
    await frame.getByLabel('新待办').fill('发布包里的持久任务')
    await frame.getByRole('button', { name: '添加待办' }).click()
    await expect(tab.getByText('已保存到数据库', { exact: true })).toBeVisible()
    await tab.reload()
    await expect(frame.getByText('发布包里的持久任务', { exact: true })).toBeVisible()
    const second = await other.newPage()
    await second.goto(publicUrl)
    await expect(second.getByText('应用已启动', { exact: true })).toBeVisible()
    await expect(second.frameLocator('iframe').getByRole('checkbox')).toHaveCount(0)
    expect(errors).toEqual([])
    await tab.screenshot({
      path: testInfo.outputPath('release-public-desktop.png'),
      fullPage: true,
    })
    await tab.setViewportSize({ width: 390, height: 844 })
    await tab.screenshot({ path: testInfo.outputPath('release-public-mobile.png'), fullPage: true })
    await panel.getByRole('button', { name: '下线应用', exact: true }).click()
    await panel.getByRole('button', { name: '确认下线', exact: true }).click()
    await expect(panel.getByText('已下线', { exact: true })).toBeVisible()
    await second.reload()
    await expect(second.getByRole('alert')).toContainText('应用未发布或已下线')
  } finally {
    await visitor.close()
    await other.close()
  }
})

test('proxy preserves API errors, rejects oversized bodies and does not expose test/static fallbacks', async ({
  request,
}) => {
  for (const path of ['/assets/absent.js', '/test-lab', '/.env']) {
    expect((await request.get(path)).status()).toBe(404)
  }
  const unknown = await request.get('/api/public/apps/' + 'a'.repeat(32))
  expect(unknown.status()).toBe(404)
  expect(unknown.headers()['content-type']).toContain('application/json')
  const oversized = await request.post('/api/session', { data: 'a'.repeat(512 * 1024 + 1) })
  expect(oversized.status()).toBe(413)
  const csrf = await request.get('/api/csrf', {
    headers: { 'X-Forwarded-Proto': 'http', 'X-Forwarded-For': '203.0.113.99' },
  })
  expect(csrf.headers()['strict-transport-security']).toContain('max-age=')
  const cookie = csrf.headers()['set-cookie'] ?? ''
  expect(
    cookie.includes('Secure') && cookie.includes('HttpOnly') && cookie.includes('SameSite=Lax'),
  ).toBe(true)
})
