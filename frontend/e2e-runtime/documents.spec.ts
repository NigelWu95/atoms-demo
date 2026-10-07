import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test('saved fixture runs in an opaque iframe and directly; management headers stay strict', async ({
  page,
  context,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  await page.goto('/test-lab')
  const response = page.waitForResponse(
    (value) => value.url().endsWith('/api/test-fixtures') && value.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '装载测试产物' }).click()
  expect((await response).status()).toBe(201)
  const frame = page.frameLocator('iframe')
  await expect(frame.getByRole('heading', { name: '测试计数器' })).toBeVisible()
  await frame.getByRole('button', { name: '增加计数' }).click()
  await expect(frame.getByRole('button', { name: '增加计数' })).toHaveText('1')
  await frame.getByRole('button', { name: '增加计数' }).press('Space')
  await expect(frame.getByRole('button', { name: '增加计数' })).toHaveText('2')
  expect(await page.locator('iframe').getAttribute('sandbox')).toBe('allow-scripts')
  const child = page.frames().find((candidate) => candidate !== page.mainFrame())
  expect(child).toBeTruthy()
  expect(await child?.evaluate(() => window.origin)).toBe('null')
  const direct = await page.getByRole('link', { name: '直接打开受限文档' }).getAttribute('href')
  expect(direct).toMatch(
    /^\/api\/projects\/[0-9a-f-]+\/versions\/[0-9a-f-]+\/document\?previewMode=CURRENT$/,
  )
  const opened = await context.newPage()
  opened.on('pageerror', (error) => errors.push(error.message))
  opened.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  const documentResponse = await opened.goto(direct!)
  expect(documentResponse?.headers()['content-security-policy']).toContain('sandbox allow-scripts')
  expect(documentResponse?.headers()['x-frame-options']).toBe('SAMEORIGIN')
  expect(await opened.evaluate(() => window.origin)).toBe('null')
  await opened.getByRole('button', { name: '增加计数' }).click()
  await expect(opened.getByRole('button', { name: '增加计数' })).toHaveText('1')
  await opened.reload()
  await expect(opened.getByRole('button', { name: '增加计数' })).toHaveText('0')
  await opened.close()
  const management = await context.request.get('/api/me')
  expect(management.headers()['x-frame-options']).toBe('DENY')
  expect(management.headers()['content-security-policy']).toContain("default-src 'none'")
  expect(management.headers()['content-security-policy']).not.toContain('unsafe-inline')
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
      .violations,
  ).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('runtime-desktop.png'), fullPage: true })
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  await page.setViewportSize({ width: 390, height: 900 })
  await page.screenshot({ path: testInfo.outputPath('runtime-mobile.png'), fullPage: true })
  expect(errors).toEqual([])
})

test('closing tags are data, not additional HTML or scripts', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/test-lab')
  await page.getByLabel('测试产物').selectOption('closing-tags')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  const frame = page.frameLocator('iframe')
  await expect(frame.locator('#checks')).toContainText('</script><script>')
  await expect(frame.locator('body')).not.toHaveAttribute('data-breakout')
  await frame.getByRole('button', { name: '增加计数' }).click()
  await expect(frame.getByRole('button', { name: '增加计数' })).toHaveText('1')
  await expect(page.locator('body')).not.toHaveAttribute('data-breakout')
  expect(errors).toEqual([])
})

test('runtime cannot access parent DOM, connect APIs or load external scripts', async ({
  page,
}) => {
  const violations: string[] = []
  const forbiddenResponses: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') violations.push(message.text())
  })
  page.on('response', (response) => {
    if (response.url().endsWith('/api/health') || response.url().endsWith('/external.js'))
      forbiddenResponses.push(response.url())
  })
  await page.goto('/test-lab')
  await page.getByLabel('测试产物').selectOption('security')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  const body = page.frameLocator('iframe').locator('body')
  await expect(body).toHaveAttribute('data-parent-access', 'blocked')
  await expect(body).toHaveAttribute('data-network', 'blocked')
  await expect(body).toHaveAttribute('data-external-script', 'blocked')
  expect(forbiddenResponses).toEqual([])
  expect(violations.some((message) => message.includes('connect-src'))).toBe(true)
  expect(violations.some((message) => message.includes('script-src'))).toBe(true)
})

test('history is explicit; a fresh browser cannot read private documents or metadata', async ({
  page,
  browser,
  context,
}) => {
  await page.goto('/test-lab')
  await page.getByLabel('测试产物').selectOption('history')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await expect(page.getByRole('status')).toContainText('当前为 V2')
  await expect(
    page.frameLocator('iframe').getByRole('heading', { name: '测试计数器' }),
  ).toBeVisible()
  const direct = await page.getByRole('link', { name: '直接打开受限文档' }).getAttribute('href')
  expect(direct).toContain('previewMode=HISTORY')
  expect((await context.request.get(direct!.replace('HISTORY', 'CURRENT'))).status()).toBe(409)
  const stranger = await browser.newContext({ baseURL: 'http://127.0.0.1:8081' })
  try {
    expect((await stranger.request.get(direct!)).status()).toBe(401)
    const tab = await stranger.newPage()
    await tab.goto('/test-lab')
    await tab.getByRole('button', { name: '装载测试产物' }).click()
    await expect(
      tab.frameLocator('iframe').getByRole('heading', { name: '测试计数器' }),
    ).toBeVisible()
    expect((await stranger.request.get(direct!)).status()).toBe(404)
    expect((await stranger.request.get(direct!.split('/document')[0]!)).status()).toBe(404)
  } finally {
    await stranger.close()
  }
})
