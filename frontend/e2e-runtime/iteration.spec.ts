import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { openTool } from './tools'

test('project drafts survive reload, detect another tab, and projects can be archived and restored', async ({
  page,
  context,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('http://127.0.0.1:5174/')
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('迭代体验验收项目')
  await expect(page.getByRole('button', { name: '创建项目' })).toBeEnabled()
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  const projectUrl = page.url()
  const input = page.getByRole('textbox', { name: '继续完善你的需求' })
  await input.fill('保留数据，增加筛选')
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.reload()
  await expect(input).toHaveValue('保留数据，增加筛选')
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  const tab = await context.newPage()
  try {
    await tab.goto(projectUrl)
    await expect(tab.getByText('草稿已保存', { exact: true })).toBeVisible()
    await tab.getByRole('textbox', { name: '继续完善你的需求' }).fill('第二页的新草稿')
    await expect(tab.getByText('草稿已保存', { exact: true })).toBeVisible()
    await input.fill('第一页的未合并修改')
    await expect(page.getByText('草稿存在冲突', { exact: true })).toBeVisible()
    await expect(input).toHaveValue('第一页的未合并修改')
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '重新读取服务器草稿' }).click()
    await expect(input).toHaveValue('第二页的新草稿')
    await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  } finally {
    await tab.close()
  }
  await page.getByRole('link', { name: '我的项目', exact: true }).click()
  await page.getByRole('searchbox', { name: '搜索项目' }).fill('迭代体验')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('link', { name: /迭代体验验收项目/ })).toHaveCount(1)
  await page.getByRole('button', { name: '归档 迭代体验验收项目', exact: true }).click()
  await expect(page.getByRole('link', { name: /迭代体验验收项目/ })).toHaveCount(0)
  await page.getByLabel('项目范围').selectOption('archived')
  await expect(page.getByRole('link', { name: /迭代体验验收项目/ })).toHaveCount(1)
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
      .violations,
  ).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('project-library.png'), fullPage: true })
  await page.getByRole('button', { name: '恢复 迭代体验验收项目', exact: true }).click()
  await page.getByLabel('项目范围').selectOption('active')
  await page.getByRole('link', { name: /迭代体验验收项目/ }).click()
  await expect(input).toHaveValue('第二页的新草稿')
  expect(errors).toEqual([])
})

test('workbench shows real version comparison while preserving preview and export entries', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-review')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  const preview = page.frameLocator('iframe')
  await expect(preview.getByRole('heading', { name: '把想法，一件件完成。' })).toBeVisible()
  expect(
    await page.locator('.preview-stage').evaluate((element) => {
      const advanced = document.querySelector('[aria-label="高级构建与进程管理"]')!
      const downloads = document.querySelector('[aria-label="版本源码导出"]')!
      return (
        Boolean(downloads.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING) &&
        Boolean(advanced.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING) &&
        advanced.closest('details')?.open === false &&
        !downloads.closest('details')
      )
    }),
  ).toBe(true)
  await openTool(page, '应用说明与修改记录')
  await page.getByRole('button', { name: '查看修改记录与差异' }).click()
  const review = page.getByRole('region', { name: '版本修改记录' })
  await expect(review).toContainText('相对 V1')
  await review.locator('summary').first().click()
  const diff = review.getByRole('region', { name: 'styles.css 逐行差异' })
  await expect(diff.getByRole('columnheader', { name: '旧行' })).toBeVisible()
  await expect(diff.getByRole('columnheader', { name: '新行' })).toBeVisible()
  await expect(diff.locator('[data-kind="add"]')).toHaveCount(2)
  await expect(diff.locator('[data-kind="remove"]')).toHaveCount(0)
  await expect(diff.locator('[data-kind="add"]').last()).toContainText('h1{letter-spacing:.01em}')
  const previewElement = await page.locator('iframe').elementHandle()
  // Both modes remain keyboard accessible; switching modes never remounts the preview.
  await review.getByRole('button', { name: '全文对比', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(review.getByRole('heading', { name: '修改前' }).first()).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(diff).toBeVisible()
  expect(await previewElement?.evaluate((element) => element.isConnected)).toBe(true)
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    if (width < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览', exact: true })
        .click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    if (width === 320)
      await page.screenshot({
        path: testInfo.outputPath('version-review-mobile.png'),
        fullPage: true,
      })
    await openTool(page, '应用说明与修改记录')
    await diff.scrollIntoViewIfNeeded()
    await expect(diff).toBeVisible()
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
  }
  // Scroll the host iframe: cross-origin child scrolling can stall in headless Chrome.
  await diff.press('Escape')
  await page.locator('iframe').scrollIntoViewIfNeeded()
  await expect(preview.getByRole('heading', { name: '把想法，一件件完成。' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('version-review.png'), fullPage: true })
  await expect(page.getByRole('button', { name: '下载预览工程 ZIP', exact: true })).toBeVisible()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  expect(errors).toEqual([])
})
