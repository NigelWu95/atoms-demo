import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const hash = 'a'.repeat(64),
  now = new Date().toISOString()

test('full project workbench keeps exact requests, preview identity and internal scrolling', async ({
  page,
}, info) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  await page.goto('/')
  await expect(page.getByText('服务已连接')).toBeVisible()
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  const csrf = await (await page.request.get('/api/csrf')).json()
  const created = await page.request.post('/api/projects', {
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': id(50) },
    data: { name: '完整工程浏览器验收' },
  })
  expect(created.status()).toBe(201)
  const project = (await created.json()).id as string
  let current = 0,
    initialized = false,
    approval = false,
    running = false,
    taskPrompt = '',
    submissions = 0,
    previewLoads = 0,
    historyCount = 1,
    failureAvailable = false,
    buildsRequested = 0
  const requests: { body: string | null; key: string | undefined }[] = []
  const versions = () =>
    Array.from({ length: current }, (_, index) => {
      const number = current - index
      return {
        id: id(number),
        versionNo: number,
        treeSha256: hash,
        fileCount: 2,
        treeBytes: 100,
        basedOnId: number > 1 ? id(number - 1) : null,
        restoredFromId: null,
        templateId: 'full-project-v1',
        createdAt: now,
      }
    })
  const task = () => ({
    id: id(10),
    status: 'SOURCE_SAVED',
    resultVersionId: id(current >= 3 ? 3 : 2),
    baseSourceVersionId: id(current >= 3 ? 2 : 1),
    cancelRequested: false,
    errorCode: null,
    createdAt: now,
  })
  const execution = (kind: string) => ({
    id: id(kind === 'RUN' ? 30 : 20),
    projectId: project,
    kind,
    sourceVersionId: id(2),
    buildId: kind === 'RUN' ? id(20) : null,
    resultVersionId: null,
    status: kind === 'RUN' ? (running ? 'READY' : 'STOPPED') : 'SUCCEEDED',
    cancelRequested: false,
    stopRequested: !running && kind === 'RUN',
    executionPending: false,
    errorCode: null,
    finishedAt: kind === 'RUN' && running ? null : now,
  })
  const pageOf = (items: unknown[]) => ({ items, nextCursor: null, hasMore: false })
  await page.route(`**/api/projects/${project}/workbench/**`, async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname.split('/workbench')[1]!
    const send = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/capabilities')
      return send({
        enabled: true,
        reason: null,
        archived: false,
        sourceReady: true,
        agentEnabled: true,
        deliveryEnabled: true,
      })
    if (path === '/source/head')
      return send({
        projectId: project,
        sourceVersionId: current ? id(current) : null,
        revision: current,
        status: current ? 'READY' : 'EMPTY',
      })
    if (path === '/source/versions') return send({ items: versions(), nextBeforeVersion: null })
    if (path === '/source/operations')
      return send(
        initialized
          ? [
              {
                id: id(40),
                kind: 'INITIALIZE',
                status: 'COMMITTED',
                resultVersionId: id(1),
                errorCode: null,
              },
            ]
          : [],
      )
    if (path === '/source/initialize') {
      initialized = true
      current = 1
      return send(
        {
          id: id(40),
          kind: 'INITIALIZE',
          status: 'COMMITTED',
          resultVersionId: id(1),
          errorCode: null,
        },
        202,
      )
    }
    if (path.endsWith('/tree'))
      return send([
        { path: 'frontend/src/App.vue', mode: 33188, bytes: 32, sha256: hash },
        {
          path: 'backend/src/main/resources/db/migration/V2__feature.sql',
          mode: 33188,
          bytes: 32,
          sha256: hash,
        },
      ])
    if (path.endsWith('/file'))
      return send({
        path: url.searchParams.get('path'),
        mode: 33188,
        bytes: 32,
        content: url.searchParams.get('path')?.endsWith('.sql')
          ? 'ALTER TABLE todos ADD COLUMN priority integer;'
          : '<template>完整工程功能</template>',
        truncated: false,
      })
    if (path === '/source/diff')
      return send({
        changes: [{ path: 'frontend/src/App.vue', kind: 'MODIFIED' }],
        preview: '-old\n+完整工程功能',
        truncated: false,
      })
    if (path === '/agent/tasks' && request.method() === 'POST') {
      requests.push({ body: request.postData(), key: request.headers()['idempotency-key'] })
      submissions++
      taskPrompt = request.postDataJSON().prompt
      current = submissions >= 3 ? 3 : 2
      if (submissions === 1) return route.abort('failed')
      return send(task(), 202)
    }
    if (path === '/agent/tasks')
      return send(
        taskPrompt
          ? Array.from({ length: historyCount }, (_, i) => ({
              task: { ...task(), id: id(100 + i) },
              prompt:
                `${taskPrompt}\n${'保留已有数据与功能。'.repeat(historyCount > 1 ? 20 : 0)}`.trim(),
            }))
          : [],
      )
    if (path.endsWith('/events') && path.includes('/agent/'))
      return send({
        events: [
          {
            sequence: 1,
            kind: 'RESULT',
            payload: { status: 'SOURCE_SAVED', cancelRequested: false },
          },
        ],
        nextCursor: 1,
        hasMore: false,
        lastSequence: 1,
      })
    if (path === '/runtime') return send({ coordinatorReady: true, databaseBound: true })
    if (path === '/builds' && request.method() === 'POST') {
      buildsRequested++
      return send(execution('BUILD'), 202)
    }
    if (path === '/builds')
      return send(
        pageOf(
          current >= 2
            ? [
                ...(failureAvailable
                  ? [
                      {
                        ...execution('BUILD'),
                        id: id(21),
                        status: 'FAILED',
                        errorCode: 'EXECUTION_CHECK_FAILED',
                      },
                    ]
                  : []),
                execution('BUILD'),
              ]
            : [],
        ),
      )
    if (path === `/execution/BUILD/${id(21)}/events`)
      return send({
        items: [
          {
            sequence: 1,
            diagnostic: {
              status: null,
              operation: 'TYPECHECK',
              probe: null,
              phase: 'failed',
              code: 'EXECUTION_CHECK_FAILED',
              rawMessage: 'private-error-secret',
            },
          },
        ],
        hasMore: false,
        nextCursor: null,
      })
    if (path === '/preparations') return send(pageOf([]))
    if (path === '/runs' && request.method() === 'POST') {
      expect(approval).toBe(true)
      running = true
      return send(execution('RUN'), 202)
    }
    if (path === '/runs') return send(pageOf(running ? [execution('RUN')] : []))
    if (path.endsWith('/migration'))
      return send({
        projectId: project,
        buildId: id(20),
        sourceVersionId: id(2),
        bindingId: id(60),
        historySha256: hash,
        targetSha256: hash,
        decision: 'REVIEW_REQUIRED',
        files: [
          {
            path: 'backend/src/main/resources/db/migration/V2__feature.sql',
            sha256: hash,
            applied: false,
          },
        ],
      })
    if (path.endsWith('/migration-approvals')) {
      approval = true
      return send({ approvalId: id(61) })
    }
    throw new Error(`Unhandled workbench fixture route ${path}`)
  })
  await page.route(`**/api/projects/${project}/delivery/**`, async (route) => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 })
    if (path.endsWith('/address'))
      return route.fulfill({
        status: 200,
        json: {
          runId: id(30),
          buildId: id(20),
          sourceVersionId: id(2),
          url: 'http://localhost:18203/',
        },
      })
    if (path.endsWith('/source.zip'))
      return route.fulfill({
        status: 200,
        contentType: 'application/zip',
        body: Buffer.from([80, 75, 3, 4, 0]),
      })
    return route.fulfill({
      status: 201,
      json: {
        previewId: id(70),
        expiresAt: new Date(Date.now() + 600_000).toISOString(),
        iframeUrl: `/api/delivery/preview/${'a'.repeat(43)}/`,
        identity: {
          runId: id(30),
          executionId: id(31),
          build: { projectId: project, buildId: id(20), sourceVersionId: id(2), treeSha256: hash },
        },
      },
    })
  })
  await page.route('**/api/delivery/preview/**', async (route) => {
    previewLoads++
    await route.fulfill({
      contentType: 'text/html; charset=utf-8',
      body: '<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>交互测试</title></head><body><main><h1>运行预览交互测试</h1><button id="counter" onclick="this.textContent=String(Number(this.textContent)+1)">0</button></main></body></html>',
    })
  })
  await page.goto(`/projects/${project}/engineering`)
  await page.getByRole('button', { name: '初始化工程', exact: true }).click()
  await expect(page.getByText('源码 V1', { exact: true })).toBeVisible()
  await page.getByLabel('描述下一步修改').fill('为待办增加优先级，并保留既有功能')
  await page.getByRole('button', { name: '提交开发需求' }).click()
  await expect(page.getByRole('button', { name: '重试原请求' })).toBeVisible()
  expect(submissions).toBe(1)
  await page.getByRole('button', { name: '重试原请求' }).click()
  await expect(page.getByText('源码 V2', { exact: true })).toBeVisible()
  expect(requests[1]).toEqual(requests[0])
  await page.reload()
  await expect(page.locator('.engineering-message')).toContainText('为待办增加优先级')
  await page.getByRole('button', { name: '构建与运行 ▾', exact: true }).click()
  await page.getByRole('button', { name: '构建选中源码', exact: true }).click()
  await page.getByRole('button', { name: '核对数据库迁移', exact: true }).click()
  await expect(page.getByRole('button', { name: '启动并本地发布', exact: true })).toBeDisabled()
  page.on('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '批准本次迁移', exact: true }).click()
  await page.getByRole('button', { name: '启动并本地发布', exact: true }).click()
  await expect(page.getByText('运行 V2', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '打开本地应用 ↗', exact: true })).toHaveAttribute(
    'href',
    'http://localhost:18203/',
  )
  await page.getByRole('button', { name: '构建与运行 ▴', exact: true }).click()
  await page.getByRole('button', { name: '打开运行预览', exact: true }).click()
  const frame = page.frameLocator('iframe[title="完整工程运行预览"]')
  await frame.locator('#counter').click()
  await expect(frame.locator('#counter')).toHaveText('1')
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(frame.locator('#counter')).toHaveText('1')
  await page
    .getByRole('group', { name: '工程内容' })
    .getByRole('button', { name: '源码', exact: true })
    .click()
  await expect(
    page.getByRole('region', { name: '源码内容' }).or(page.locator('pre[aria-label="源码内容"]')),
  ).toContainText('完整工程功能')
  await page
    .getByRole('group', { name: '工程内容' })
    .getByRole('button', { name: '差异', exact: true })
    .click()
  await expect(page.locator('.engineering-diff')).toContainText('+完整工程功能')
  await page
    .getByRole('group', { name: '工程内容' })
    .getByRole('button', { name: '预览', exact: true })
    .click()
  await expect(frame.locator('#counter')).toHaveText('1')
  expect(previewLoads).toBe(1)
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载当前预览源码', exact: true }).click()
  expect((await downloaded).suggestedFilename()).toContain(id(70))
  // Preparing/cancelling a repair is read-only. Confirmation keeps the independent idea draft and serving preview.
  const idea = page.getByLabel('描述下一步修改', { exact: true })
  await idea.fill('稍后还要增加导出功能')
  failureAvailable = true
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '构建与运行 ▾', exact: true }).click()
  await page.getByLabel('构建记录', { exact: true }).selectOption(id(21))
  await page.getByRole('button', { name: '准备修复需求', exact: true }).click()
  await expect(page.getByLabel('将发送的构建修复需求')).toContainText('前端类型检查')
  await expect(page.getByLabel('将发送的构建修复需求')).not.toContainText('private-error-secret')
  expect(submissions).toBe(2)
  await page.getByRole('button', { name: '取消建议', exact: true }).click()
  expect(submissions).toBe(2)
  await expect(idea).toHaveValue('稍后还要增加导出功能')
  await page.getByRole('button', { name: '准备修复需求', exact: true }).click()
  await page.getByLabel('补充修复要求（可选）').fill('保留全部已有业务功能')
  await page.getByRole('button', { name: '确认提交修复', exact: true }).click()
  await expect(page.getByText('源码 V3', { exact: true })).toBeVisible()
  expect(submissions).toBe(3)
  expect(buildsRequested).toBe(1)
  expect(JSON.parse(requests[2]!.body!)).toMatchObject({
    baseSourceVersionId: id(2),
    expectedSourceRevision: 2,
  })
  expect(JSON.parse(requests[2]!.body!).prompt).toContain('保留全部已有业务功能')
  await expect(idea).toHaveValue('稍后还要增加导出功能')
  await expect(frame.locator('#counter')).toHaveText('1')
  expect(previewLoads).toBe(1)
  historyCount = 30
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.locator('.engineering-message')).toHaveCount(30)
  expect(
    await page
      .locator('.engineering-messages')
      .evaluate((element) => element.scrollHeight > element.clientHeight),
  ).toBe(true)
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    if (width < 760)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '工程与预览', exact: true })
        .click()
    await expect(page.locator('iframe[title="完整工程运行预览"]')).toBeVisible()
    const size = await page.evaluate(() => ({
      viewport: innerHeight,
      body: document.documentElement.scrollHeight,
      wide: document.documentElement.scrollWidth,
      width: innerWidth,
      frame: document.querySelector('iframe')!.getBoundingClientRect().height,
    }))
    expect(size.body).toBeLessThanOrEqual(size.viewport + 2)
    expect(size.wide).toBeLessThanOrEqual(size.width)
    expect(size.frame).toBeGreaterThan(220)
    await page.screenshot({ path: info.outputPath(`engineering-${width}.png`) })
  }
  const accessibility = await new AxeBuilder({ page }).include('.engineering-page').analyze()
  expect(accessibility.violations).toEqual([])
  expect(pageErrors).toEqual([])
})
