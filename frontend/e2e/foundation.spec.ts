import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test('real API, project navigation, keyboard entry and disabled generation', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  const health = page.waitForResponse('**/api/health')
  const pageResponse = await page.goto('/')
  expect(pageResponse?.headers()['x-content-type-options']).toBe('nosniff')
  expect(pageResponse?.headers()['content-security-policy']).toContain("frame-ancestors 'none'")
  expect((await health).status()).toBe(200)
  await expect(page.getByText('服务已连接')).toBeVisible()
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
  await page.getByRole('button', { name: '待办清单' }).click()
  await expect(page).toHaveURL(/\/$/)
  const input = page.getByRole('textbox', { name: '描述你的应用想法' })
  await input.fill('一个可添加任务的待办工具')
  await input.press('Shift+Enter')
  await expect(page).toHaveURL(/\/$/)
  await input.press('Enter')
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  await expect(page.getByRole('heading', { name: '还没有生成应用' })).toBeVisible()
  await expect(page.getByRole('button', { name: '生成应用' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '发布应用' })).toBeDisabled()
  await page.getByRole('group', { name: '产物视图' }).getByRole('button', { name: '代码' }).click()
  await expect(page.getByRole('heading', { name: '还没有可查看的源码' })).toBeVisible()
  await page.getByRole('link', { name: '返回创作' }).click()
  await expect(page.getByRole('textbox')).toHaveValue('一个可添加任务的待办工具')
  expect(errors).toEqual([])
})

for (const width of [320, 390, 768, 1024, 1440]) {
  test(`responsive and accessible homepage/workbench at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/')
    await expect(page.getByText('服务已连接')).toBeVisible()
    await expect(page.locator('.sidebar')).toHaveCount(0)
    await expect(page.getByRole('link', { name: '注册 / 登录', exact: true })).toBeVisible()
    const footer = await page.locator('.entry-footer').boundingBox()
    const health = await page.locator('.entry-footer .service-status').boundingBox()
    expect(footer).not.toBeNull()
    expect(health).not.toBeNull()
    expect(health!.y).toBeGreaterThanOrEqual(footer!.y)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    if ([390, 1440].includes(width))
      await page.screenshot({ path: testInfo.outputPath(`home-${width}.png`), fullPage: true })
    await page.getByRole('link', { name: '我的项目', exact: true }).click()
    await page.getByRole('link', { name: '工作台', exact: true }).click()
    if (width < 768) {
      await expect(page.getByRole('heading', { name: '需求与对话' })).toBeVisible()
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览' })
        .click()
      await expect(page.getByRole('heading', { name: '需求与对话' })).toBeHidden()
    }
    await expect(page.getByRole('heading', { name: '还没有生成应用' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    if ([390, 1440].includes(width))
      await page.screenshot({ path: testInfo.outputPath(`workbench-${width}.png`), fullPage: true })
  })
}

test('connection failure is visible and a retry uses the real backend', async ({ page }) => {
  await page.route('**/api/health', (route) => route.fulfill({ status: 503, body: '{}' }))
  await page.goto('/')
  await expect(page.getByText('服务连接失败')).toBeVisible()
  await page.unroute('**/api/health')
  await page.getByRole('button', { name: '重试连接' }).click()
  await expect(page.getByText('服务已连接')).toBeVisible()
})

test('workbench deep link reloads and unknown routes have a recovery link', async ({ page }) => {
  await page.goto('/workbench')
  await page.reload()
  await expect(page.getByRole('heading', { name: '需求草稿' })).toBeVisible()
  await page.goto('/missing-page')
  await expect(page.getByRole('heading', { name: '这里还没有页面' })).toBeVisible()
  await page.getByRole('link', { name: '返回首页', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '描述你的应用想法' })).toBeVisible()
})
