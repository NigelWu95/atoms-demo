import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test('normal workbench explains missing model configuration without inventing progress', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  await page.route('**/api/generation', (route) =>
    route.fulfill({
      json: {
        configured: false,
        workerEnabled: true,
        remainingRequests: 0,
        model: 'deepseek-flash',
      },
    }),
  )
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('主流程生成测试')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page.getByText('未配置 DeepSeek API Key，请在服务端完成配置。')).toBeVisible()
  await expect(page.getByRole('button', { name: '生成应用' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '刷新生成状态' })).toBeVisible()
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
  }
  expect(errors).toEqual([])
})

test('normal workbench submits a stable intent, recovers a running task on reload and cancels it', async ({
  page,
}, testInfo) => {
  // Only API responses are simulated; no test output is installed in the normal database.
  let projectId = ''
  let status = 'RUNNING'
  let submitted = false
  let loseReply = true
  const keys: string[] = []
  const task = () => ({
    id: 'test-run',
    projectId,
    prompt: '创建交互任务板',
    status,
    stage: status === 'RUNNING' ? 'BUILDING' : status,
    resultVersionId: null,
    errorCode: status === 'CANCELLED' ? 'RUN_CANCELLED' : null,
    errorMessage: null,
    lastEventSeq: 2,
  })
  await page.route('**/api/generation', (route) =>
    route.fulfill({
      json: {
        configured: true,
        workerEnabled: true,
        remainingRequests: 6,
        model: 'deepseek-flash',
      },
    }),
  )
  await page.route('**/runs?*', (route) =>
    route.fulfill({ json: { items: submitted ? [task()] : [], nextCursor: null } }),
  )
  await page.route('**/messages?*', (route) =>
    route.fulfill({
      json: {
        items: submitted
          ? [
              {
                id: 'message-1',
                runId: 'test-run',
                role: 'USER',
                content: '创建交互任务板',
                createdAt: '2026-10-07T00:00:00Z',
              },
            ]
          : [],
        nextCursor: null,
      },
    }),
  )
  await page.route('**/api/projects/*/runs', async (route) => {
    keys.push(route.request().headers()['idempotency-key']!)
    expect(route.request().postDataJSON()).toEqual({
      prompt: '创建交互任务板',
      intent: 'CREATE',
      baseVersionId: null,
      expectedProjectRevision: 0,
    })
    submitted = true
    if (loseReply) {
      loseReply = false
      return route.abort('failed')
    }
    await route.fulfill({ status: 202, json: { runId: 'test-run', status: 'QUEUED' } })
  })
  await page.route('**/api/runs/test-run/cancellation', (route) => {
    status = 'CANCELLED'
    return route.fulfill({ json: task() })
  })
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('创建交互任务板')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  projectId = page.url().split('/').at(-1)!
  await page.getByRole('button', { name: '生成应用' }).click()
  await expect(page.getByRole('alert')).toContainText('操作结果尚不确定')
  await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toHaveValue(
    '创建交互任务板',
  )
  await page.getByRole('button', { name: '重试原提交' }).click()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBe(keys[1])
  await expect(page.getByText('正在生成代码', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByText('正在生成代码', { exact: true })).toBeVisible()
  await expect(page.getByRole('article').filter({ hasText: '创建交互任务板' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('generation-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.screenshot({ path: testInfo.outputPath('generation-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: '取消生成' }).click()
  await expect(page.getByText('已取消', { exact: true })).toBeVisible()
  expect(keys).toHaveLength(2)
})
