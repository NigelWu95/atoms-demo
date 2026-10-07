import { expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { test } from './sseFixture'

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
  await page.route('**/api/runs/test-run/events?*', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: ':connected\nretry:2000\n\n',
    }),
  )
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

test('native SSE reconnect replays its cursor, deduplicates events and reads the final snapshot', async ({
  page,
  sseServer,
}, testInfo) => {
  // Explicit HTTP fixture for generation; the browser uses its real EventSource implementation.
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  let projectId = ''
  let submitted = false
  let posts = 0
  let status = 'RUNNING'
  let stage = 'QUEUED'
  let seq = 1
  const requests: Array<{ after: string | null; last: string | undefined }> = []
  let finish!: () => void
  const finished = new Promise<void>((resolve) => {
    finish = resolve
  })
  const frame = (type: string, number: number, data: Record<string, unknown>) =>
    `id:${number}\nevent:${type}\ndata:${JSON.stringify({ runId: 'sse-run', seq: number, createdAt: '2026-10-07T00:00:00Z', ...data })}\n\n`
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
    route.fulfill({
      json: {
        items: submitted
          ? [
              {
                id: 'sse-run',
                projectId,
                prompt: '验证实时进度',
                status,
                stage,
                resultVersionId: null,
                errorCode: status === 'CANCELLED' ? 'RUN_CANCELLED' : null,
                errorMessage: null,
                lastEventSeq: seq,
              },
            ]
          : [],
        nextCursor: null,
      },
    }),
  )
  await page.route('**/messages?*', (route) =>
    route.fulfill({
      json: {
        items: !submitted
          ? []
          : [
              ...(seq >= 3
                ? [
                    {
                      id: 'plan',
                      runId: 'sse-run',
                      role: 'PLANNER',
                      content: '这是明确的 SSE 测试规划',
                      createdAt: '2026-10-07T00:00:01Z',
                    },
                  ]
                : []),
              {
                id: 'user',
                runId: 'sse-run',
                role: 'USER',
                content: '验证实时进度',
                createdAt: '2026-10-07T00:00:00Z',
              },
            ],
        nextCursor: null,
      },
    }),
  )
  await page.route('**/api/projects/*/runs', (route) => {
    posts++
    submitted = true
    return route.fulfill({ status: 202, json: { runId: 'sse-run', status: 'QUEUED' } })
  })
  sseServer.handle((request, response) => {
    requests.push({
      after: new URL(request.url!, sseServer.url).searchParams.get('after'),
      last: request.headers['last-event-id'] as string | undefined,
    })
    response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' })
    let body = 'retry:100\n\n'
    if (requests.length === 1) {
      seq = 2
      stage = 'PLANNING'
      body += frame('stage.started', 2, { stage })
    } else if (requests.length === 2) {
      seq = 4
      stage = 'BUILDING'
      body +=
        frame('stage.started', 2, { stage: 'PLANNING' }) +
        frame('plan.ready', 3, {}) +
        frame('stage.started', 4, { stage })
    } else {
      response.write(body)
      void finished.then(() => {
        seq = 5
        stage = status = 'CANCELLED'
        response.end(frame('run.completed', 5, { status, versionId: '', lastEventSeq: 5 }))
      })
      return
    }
    response.end(body)
  })
  await page.route('**/api/runs/sse-run/events?*', (route) => {
    const url = new URL(route.request().url())
    return route.continue({ url: `${sseServer.url}${url.pathname}${url.search}` })
  })
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('验证实时进度')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  projectId = page.url().split('/').at(-1)!
  await page.getByRole('button', { name: '生成应用', exact: true }).click()
  await expect(page.getByText('正在生成代码', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('article').filter({ hasText: '这是明确的 SSE 测试规划' }),
  ).toHaveCount(1)
  await expect.poll(() => requests.length).toBe(3)
  expect(requests.map((request) => request.last)).toEqual([undefined, '2', '4'])
  expect(requests.every((request) => request.after === '1')).toBe(true)
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
  }
  await page.screenshot({ path: testInfo.outputPath('sse-building-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.screenshot({ path: testInfo.outputPath('sse-building-mobile.png'), fullPage: true })
  finish()
  await expect(page.getByText('已取消', { exact: true })).toBeVisible()
  await expect(page.getByText('进度连接中断，正在重连并补齐事件…')).toHaveCount(0)
  expect(posts).toBe(1)
  expect(requests).toHaveLength(3)
  expect(errors).toEqual([])
})
