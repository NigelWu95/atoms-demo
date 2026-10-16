import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [320, 768, 1024, 1440]) {
  test(`long application information leaves the live frame first at ${width}px`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const issues: string[] = []
    page.on('pageerror', (error) => issues.push(error.message))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) issues.push(message.text())
    })
    // Only presentation metadata is replaced; session, document, SDK and storage are real.
    const summaryText =
      '保留原有任务、筛选、搜索和完成状态。'.repeat(90) + '<img src=x onerror=alert(1)>'
    await page.route('**/api/app', async (route) => {
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      await route.fulfill({ response, json: { ...(await response.json()), summary: summaryText } })
    })
    await page.goto('/')
    const frame = page.frameLocator('iframe')
    const input = frame.getByRole('textbox', { name: '新待办', exact: true })
    await expect(input).toBeEnabled()
    const iframe = page.locator('iframe')
    const frameBox = await iframe.boundingBox()
    expect(frameBox?.y).toBeLessThanOrEqual(480)
    await info.attach('preview-layout', {
      body: JSON.stringify({ width, iframeTop: frameBox?.y }),
      contentType: 'application/json',
    })
    const originalFrame = await iframe.elementHandle()
    const channel = await iframe.getAttribute('src')
    await input.fill('展开说明不丢失的输入')
    await frame.getByRole('button', { name: '本页计数：0' }).click()
    const details = page.locator('details')
    const summary = details.locator('summary')
    const description = details.locator('p').first()
    await expect(details).toHaveJSProperty('open', false)
    await expect(description).not.toBeVisible()
    expect(await description.textContent()).toBe(summaryText)
    await expect(details.locator('img')).toHaveCount(0)
    const apiRequests: string[] = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.method())
    })
    await summary.focus()
    await page.keyboard.press('Enter')
    await expect(details).toHaveJSProperty('open', true)
    await expect(description).toBeVisible()
    await expect(summary).toBeFocused()
    expect((await summary.boundingBox())?.height).toBeGreaterThanOrEqual(44)
    const audit = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze()
    expect(audit.violations).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.keyboard.press('Space')
    await expect(details).toHaveJSProperty('open', false)
    await expect(input).toHaveValue('展开说明不丢失的输入')
    await expect(frame.getByRole('button', { name: '本页计数：1' })).toBeVisible()
    expect(await iframe.evaluate((node, original) => node === original, originalFrame)).toBe(true)
    expect(await iframe.getAttribute('src')).toBe(channel)
    expect(apiRequests).toEqual([])
    await originalFrame?.dispose()
    await page.getByRole('link', { name: '跳到主要内容' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('main')).toBeFocused()
    await page.screenshot({ path: info.outputPath(`preview-first-${width}.png`), fullPage: true })
    expect(issues).toEqual([])
  })
}
