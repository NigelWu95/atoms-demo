import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function openLongConversation(page: Page) {
  await page.route('**/messages?*', (route) =>
    route.fulfill({
      json: {
        items: Array.from({ length: 20 }, (_, index) => ({
          id: `layout-message-${index}`,
          runId: 'layout-run',
          role: index % 2 ? 'USER' : 'PLANNER',
          content: `第 ${20 - index} 条需求。${'保留游戏玩法，并继续增加键盘操作和排行榜。'.repeat(30)}`,
          createdAt: '2026-10-18T00:00:00Z',
        })),
        nextCursor: null,
      },
    }),
  )
  await page.route(/\/api\/projects\/[^/]+\/versions\/[^/?]+$/, async (route) => {
    const response = await route.fetch()
    const version = await response.json()
    version.artifact.manifest.summary = '完整应用说明与数据持久化边界。'.repeat(80)
    await route.fulfill({ response, json: version })
  })
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-history')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  await expect(page.locator('.generation-message')).toHaveCount(20)
}

async function expectBoundedPage(page: Page) {
  expect(
    await page.evaluate(() => ({
      width: document.documentElement.scrollWidth - innerWidth,
      height: document.documentElement.scrollHeight - innerHeight,
    })),
  ).toEqual({ width: 0, height: 0 })
}

test('empty workbench remains bounded and keyboard accessible on a short mobile screen', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 600 })
  await page.goto('http://127.0.0.1:5174/workbench')
  await expect(page.getByRole('heading', { name: '需求草稿 未保存' })).toBeVisible()
  await expectBoundedPage(page)
  await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toBeInViewport({ ratio: 1 })
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
      .violations,
  ).toEqual([])
})

for (const [width, height] of [
  [1440, 900],
  [1024, 768],
  [1280, 600],
  [768, 900],
  [390, 844],
  [320, 700],
]) {
  test(`long workbench scrolls inside panels at ${width}x${height}`, async ({ page }, info) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (['warning', 'error'].includes(message.type())) errors.push(message.text())
    })
    await openLongConversation(page)
    await page.setViewportSize({ width: width!, height: height! })
    await expect(page.getByRole('button', { name: '展开侧栏查看账号', exact: true })).toBeHidden()
    if (width! < 768) await expect(page.locator('.sidebar-toggle')).toBeHidden()
    await page.screenshot({ path: info.outputPath('workbench-initial.png'), fullPage: true })
    await expectBoundedPage(page)
    const composer = page.getByRole('textbox', { name: '继续完善你的需求' })
    await expect(composer).toBeInViewport({ ratio: 1 })
    const history = page.locator('.generation-conversation')
    const before = await history.evaluate((element) => element.scrollTop)
    await history.hover()
    await page.mouse.wheel(0, 700)
    await expect
      .poll(() => history.evaluate((element) => element.scrollTop))
      .toBeGreaterThan(before)
    await expectBoundedPage(page)
    await expect(composer).toBeInViewport({ ratio: 1 })
    await composer.fill('内部滚动后仍保留的草稿')
    if (width! < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览', exact: true })
        .click()
    const frame = page.locator('iframe')
    await expect(frame).toBeInViewport({ ratio: 0.35 })
    const frameTop = await frame.evaluate((element) => element.getBoundingClientRect().top)
    expect(frameTop).toBeLessThan(height! * 0.65)
    await page.frameLocator('iframe').getByLabel('新待办').fill('预览功能未改变')
    await page.screenshot({ path: info.outputPath('workbench-preview.png'), fullPage: true })
    await expectBoundedPage(page)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    await page
      .getByRole('button', { name: '导出版本源码 ZIP', exact: true })
      .scrollIntoViewIfNeeded()
    await expectBoundedPage(page)
    if (width! < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '对话', exact: true })
        .click()
    await expect(composer).toHaveValue('内部滚动后仍保留的草稿')
    await expect(composer).toBeInViewport({ ratio: 1 })
    expect(errors).toEqual([])
  })
}

test('keyboard scrolling and expanded tools never grow the document or discard preview input', async ({
  page,
}) => {
  await openLongConversation(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  const history = page.getByRole('region', { name: '对话记录', exact: true })
  await history.focus()
  await page.keyboard.press('End')
  await expect.poll(() => history.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  await expectBoundedPage(page)
  await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toBeInViewport({ ratio: 1 })

  const composer = page.getByRole('textbox', { name: '继续完善你的需求' })
  await composer.fill('侧栏和工具开合不丢失草稿')
  const frame = await page.locator('iframe').elementHandle()
  await page.frameLocator('iframe').getByLabel('新待办').fill('工具切换不重载')
  await page.getByRole('button', { name: '收起侧边栏', exact: true }).click()
  await expect(page.locator('.sidebar')).toHaveCSS('width', '72px')
  await expect(page.getByRole('link', { name: '开始创作', exact: true })).toBeVisible()
  await expectBoundedPage(page)
  await page.getByRole('button', { name: '展开侧边栏', exact: true }).press('Enter')
  await expect(composer).toHaveValue('侧栏和工具开合不丢失草稿')
  for (const label of ['预览检查', '构建与进程', '下载说明']) {
    const summary = page.locator('.tool-disclosure > summary').filter({ hasText: label })
    await summary.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('region', { name: label, exact: true })).toBeVisible()
    await expectBoundedPage(page)
    await page.keyboard.press('Escape')
    await expect(summary).toBeFocused()
    await expect(page.getByRole('region', { name: label, exact: true })).toBeHidden()
  }
  expect(await frame!.evaluate((element) => element.isConnected)).toBe(true)
  await expect(page.frameLocator('iframe').getByLabel('新待办')).toHaveValue('工具切换不重载')

  const description = page.locator('.artifact-description')
  await description.locator(':scope > summary').click()
  await expect(description.locator('.artifact-summary')).toBeVisible()
  await expectBoundedPage(page)
  await description.locator(':scope > summary').click()

  await page.getByText('工作台说明', { exact: true }).click()
  await expect(page.locator('.workbench-notice')).toBeVisible()
  await expectBoundedPage(page)
  await page.getByText('工作台说明', { exact: true }).click()
  await page.getByText('数据与隐私说明', { exact: true }).click()
  await expect(page.locator('#prompt-hint')).toBeVisible()
  await expectBoundedPage(page)
  await page.getByText('数据与隐私说明', { exact: true }).click()

  await page.getByRole('button', { name: '重命名', exact: true }).click()
  await page.getByLabel('项目名称', { exact: true }).fill('长项目名称'.repeat(15))
  await page.getByRole('button', { name: '保存名称', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('长项目名称'.repeat(15))
  await expectBoundedPage(page)
  await page.getByRole('button', { name: '发布应用 ↗', exact: true }).click()
  const publication = page.getByRole('region', { name: '应用发布', exact: true })
  await publication.getByRole('button', { name: '刷新发布状态', exact: true }).focus()
  await expect(
    publication.getByRole('button', { name: '刷新发布状态', exact: true }),
  ).toBeInViewport({ ratio: 1 })
  await expectBoundedPage(page)
  await page.getByRole('button', { name: '发布应用 ↗', exact: true }).click()

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '导航与账号', exact: true }).click()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()
  await expect(page.getByRole('link', { name: '邮箱注册 / 登录', exact: true })).toBeVisible()
  await expectBoundedPage(page)
  await page.getByRole('button', { name: '收起导航', exact: true }).click()
  await page
    .getByRole('group', { name: '工作台区域' })
    .getByRole('button', { name: '预览', exact: true })
    .click()
  await page.frameLocator('iframe').getByLabel('新待办').fill('切换对话区域不重载应用')
  await page
    .getByRole('group', { name: '工作台区域' })
    .getByRole('button', { name: '对话', exact: true })
    .click()
  await page
    .getByRole('group', { name: '工作台区域' })
    .getByRole('button', { name: '预览', exact: true })
    .click()
  await expect(page.frameLocator('iframe').getByLabel('新待办')).toHaveValue(
    '切换对话区域不重载应用',
  )
  await expectBoundedPage(page)

  await page.getByRole('button', { name: '导航与账号', exact: true }).click()
  await page
    .getByRole('navigation', { name: '主要导航' })
    .getByRole('link', { name: '开始创作' })
    .click()
  await expect(page).toHaveURL(/\/create$/)
  await expect(page.locator('.workbench-shell')).toHaveCount(0)
  await page.getByRole('link', { name: 'Atoms Demo Studio 首页', exact: true }).click()
  await expect(page.locator('.sidebar')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollHeight > innerHeight)).toBe(true)
})
