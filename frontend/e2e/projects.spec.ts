import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test('guest creates, renames and reopens persisted project; another guest cannot read it', async ({
  page,
  browser,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('一个支持任务筛选的待办应用')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  const projectUrl = page.url()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('一个支持任务筛选的待办应用')
  await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toHaveValue(
    '一个支持任务筛选的待办应用',
  )
  await page.getByRole('button', { name: '重命名', exact: true }).click()
  await page.getByRole('textbox', { name: '项目名称', exact: true }).fill('我的任务工作台')
  await page.getByRole('button', { name: '保存名称' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('我的任务工作台')
  await page.reload()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('我的任务工作台')
  await expect(page.getByText('项目名称已保存', { exact: true })).toBeVisible()
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
      .violations,
  ).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('project-desktop.png'), fullPage: true })
  await page.getByRole('link', { name: '我的项目', exact: true }).click()
  await expect(page.getByRole('link', { name: /我的任务工作台/ })).toBeVisible()
  await page.reload()
  await page.getByRole('link', { name: /我的任务工作台/ }).click()
  await expect(page).toHaveURL(projectUrl)
  await page.setViewportSize({ width: 390, height: 1000 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
      .violations,
  ).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('project-mobile.png'), fullPage: true })
  const stranger = await browser.newContext()
  try {
    const tab = await stranger.newPage()
    await tab.goto(projectUrl)
    await expect(tab.getByRole('heading', { name: '项目无法打开' })).toBeVisible()
    await expect(tab.getByText('我的任务工作台')).toHaveCount(0)
    await tab.getByRole('link', { name: '返回我的项目', exact: true }).click()
    await expect(tab.getByRole('heading', { name: '还没有项目，从一个想法开始' })).toBeVisible()
  } finally {
    await stranger.close()
  }
  expect(errors).toEqual([])
})

test('failed create retains input; explicit retry reuses intent after response is lost', async ({
  page,
}) => {
  const keys: string[] = []
  await page.route('**/api/projects', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push(route.request().headers()['idempotency-key'] ?? '')
    if (keys.length === 1) {
      await route.fetch() // Server commits; only the reply is lost.
      await route.abort('failed')
    } else await route.continue()
  })
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  const input = page.getByRole('textbox', { name: '描述你的应用想法' })
  await input.fill('重试不重复创建的项目')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page.getByRole('alert')).toContainText('操作结果尚不确定')
  await expect(input).toHaveValue('重试不重复创建的项目')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[0]).toBe(keys[1])
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '我的项目', exact: true }).click()
  await expect(page.getByRole('link', { name: /重试不重复创建的项目/ })).toHaveCount(1)
})

test('session and list failures have recovery actions, and session failure disables creation', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 1000 })
  await page.route('**/api/session', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({
        error: { code: 'SERVICE_UNAVAILABLE', message: '暂时无法建立会话', retryable: true },
        requestId: 'test-session',
      }),
    }),
  )
  await page.goto('/')
  await page.getByRole('textbox').fill('暂时不能提交')
  await expect(page.getByRole('button', { name: '创建项目' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '重新连接会话' })).toBeVisible()
  await page.unroute('**/api/session')
  await page.getByRole('button', { name: '重新连接会话' }).click()
  await expect(page.getByRole('button', { name: '创建项目' })).toBeEnabled()
  await page.route('**/api/projects?*', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({
        error: { code: 'SERVICE_UNAVAILABLE', message: '项目读取暂时失败', retryable: true },
        requestId: 'test-list',
      }),
    }),
  )
  await page.getByRole('link', { name: '我的项目', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('项目读取暂时失败')
  await page.unroute('**/api/projects?*')
  await page.getByRole('button', { name: '重试加载项目' }).click()
  await expect(page.getByRole('heading', { name: '还没有项目，从一个想法开始' })).toBeVisible()
})
