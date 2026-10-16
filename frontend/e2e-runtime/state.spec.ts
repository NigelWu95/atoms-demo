import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function openTodo(page: Page, fixture = 'todo') {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption(fixture)
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  return page.frameLocator('iframe')
}
test('generated projects retain their status on home, project list and reload', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  await openTodo(page)
  const path = new URL(page.url()).pathname
  await page.getByRole('link', { name: '← 返回创作' }).click()
  const row = page.locator(`.project-row[href="${path}"]`)
  await expect(row).toContainText('已生成')
  await expect(row).not.toContainText('尚未生成')
  await expect(page.getByText('AI 生成稍后接入', { exact: false })).toHaveCount(0)
  await page.getByRole('link', { name: '我的项目', exact: true }).click()
  await expect(row).toContainText('已生成')
  await page.reload()
  await expect(row).toContainText('已生成')
  await expect(row).not.toContainText('尚未生成')
  await page.screenshot({
    path: testInfo.outputPath('generated-project-status.png'),
    fullPage: true,
  })
  await row.click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  expect(errors).toEqual([])
})
test('todo adds, completes, survives reload and shows real source in a private wrapper', async ({
  page,
  context,
  browser,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  const frame = await openTodo(page)
  await frame.getByLabel('新待办').fill('检查持久化 <b>不是标签</b>')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  await frame.getByRole('checkbox').check()
  await expect(frame.getByRole('checkbox')).toBeChecked()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  await page.reload()
  await expect(frame.getByRole('checkbox')).toBeChecked()
  await expect(frame.getByText('检查持久化 <b>不是标签</b>', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '代码', exact: true }).click()
  await page.getByRole('button', { name: 'view.html', exact: true }).click()
  await expect(page.locator('pre')).toContainText('<main>')
  await expect(page.locator('pre main')).toHaveCount(0)
  await page.getByRole('button', { name: 'manifest.json', exact: true }).click()
  await expect(page.locator('pre')).toContainText('web-static-v1')
  await page.getByRole('button', { name: '预览', exact: true }).last().click()
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
      .violations,
  ).toEqual([])
  await page.screenshot({
    path: testInfo.outputPath('workbench-preview-desktop.png'),
    fullPage: true,
  })
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  await page.setViewportSize({ width: 390, height: 900 })
  await page
    .getByRole('group', { name: '工作台区域' })
    .getByRole('button', { name: '预览', exact: true })
    .click()
  await page.screenshot({
    path: testInfo.outputPath('workbench-preview-mobile.png'),
    fullPage: true,
  })
  const href = await page.getByRole('link', { name: '共享预览新窗口' }).getAttribute('href')
  const tab = await context.newPage()
  await tab.goto(`http://127.0.0.1:5174${href}`)
  await expect(tab.frameLocator('iframe').getByRole('checkbox')).toBeChecked()
  const stranger = await browser.newContext()
  try {
    const other = await stranger.newPage()
    await other.goto(`http://127.0.0.1:5174${href}`)
    await expect(other.getByRole('alert')).toContainText('项目不存在或当前身份无权访问')
    await expect(other.locator('iframe')).toHaveCount(0)
  } finally {
    await stranger.close()
    await tab.close()
  }
  expect(errors).toEqual([])
})

test('unknown response retries same save key and preserves draft; two tabs conflict visibly', async ({
  page,
  context,
}) => {
  const frame = await openTodo(page)
  const second = await context.newPage()
  await second.goto(page.url())
  await expect(second.getByText('应用已启动', { exact: true })).toBeVisible()
  const keys: string[] = []
  let loseResponse = true
  await page.route('**/preview-state', async (route) => {
    if (route.request().method() !== 'PUT') return route.continue()
    keys.push(route.request().headers()['idempotency-key']!)
    if (loseResponse) {
      loseResponse = false
      await route.fetch()
      await route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
    } else await route.continue()
  })
  await frame.getByLabel('新待办').fill('保留输入并重试')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(frame.getByRole('button', { name: '重试保存' })).toBeVisible()
  await expect(frame.getByLabel('新待办')).toHaveValue('保留输入并重试')
  await frame.getByRole('button', { name: '重试保存' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBe(keys[1])
  const otherFrame = second.frameLocator('iframe')
  await otherFrame.getByLabel('新待办').fill('另一标签的输入')
  await otherFrame.getByRole('button', { name: '添加待办' }).click()
  await expect(second.getByText(/数据已在另一处更新/).first()).toBeVisible()
  await expect(otherFrame.getByLabel('新待办')).toHaveValue('另一标签的输入')
  await otherFrame.getByRole('button', { name: '读取最新数据' }).click()
  await expect(otherFrame.getByText('保留输入并重试', { exact: true })).toBeVisible()
  await second.close()
})

test('history uses initial state only and refuses saving', async ({ page }) => {
  const frame = await openTodo(page, 'todo-history')
  await frame.getByLabel('新待办').fill('当前版本真实数据')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  const requests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/preview-state')) requests.push(request.url())
  })
  await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V1 · 历史只读' })
  await expect(page.getByText('历史只读 · 使用初始数据')).toBeVisible()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  await expect(frame.getByRole('checkbox')).toHaveCount(0)
  await frame.getByLabel('新待办').fill('不能保存历史数据')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(page.getByText(/历史预览只读，不能保存/).first()).toBeVisible()
  expect(requests).toEqual([])
  await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V2 · 当前版本' })
  await expect(frame.getByText('当前版本真实数据', { exact: true })).toBeVisible()
})

test('offline saving retains input and recovers without duplicating the intent', async ({
  page,
  context,
}) => {
  const frame = await openTodo(page)
  await context.setOffline(true)
  await frame.getByLabel('新待办').fill('离线输入不丢失')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(frame.getByRole('button', { name: '重试保存' })).toBeVisible()
  await expect(frame.getByLabel('新待办')).toHaveValue('离线输入不丢失')
  await expect(page.getByText(/连接中断，操作结果尚不确定/).first()).toBeVisible()
  await context.setOffline(false)
  await frame.getByRole('button', { name: '重试保存' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  await page.reload()
  await expect(frame.getByRole('checkbox')).toHaveCount(1)
  await expect(frame.getByText('离线输入不丢失', { exact: true })).toBeVisible()
})
