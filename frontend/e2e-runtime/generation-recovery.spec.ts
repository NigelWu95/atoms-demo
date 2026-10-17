import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const cacheKey = 'atoms.generation.recovery.v1'
async function workbench(page: Page) {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-review')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toBeVisible()
}
test('committed response loss survives reload and an opener copy replays the same real run', async ({
  page,
  context,
}, testInfo) => {
  test.setTimeout(60000)
  const faults: string[] = []
  page.on('pageerror', (error) => faults.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) faults.push(message.text())
  })
  // Real admission/idempotency/DB, but execution must be disabled. Never call a paid model.
  await context.route('**/api/generation', async (route) => {
    const response = await route.fetch()
    const availability = await response.json()
    expect(availability.workerEnabled).toBe(false)
    expect(availability.configured).toBe(true)
    await route.fulfill({ response, json: { ...availability, workerEnabled: true } })
  })
  const requests: { body: string; key: string; runId: string }[] = []
  let runsUrl = ''
  await context.route(/\/api\/projects\/[^/]+\/runs(?:\?.*)?$/, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    runsUrl = route.request().url()
    const response = await route.fetch()
    expect(response.status()).toBe(202)
    const accepted = await response.json()
    requests.push({
      body: route.request().postData()!,
      key: route.request().headers()['idempotency-key']!,
      runId: accepted.runId,
    })
    if (requests.length === 1)
      await route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
    else await route.fulfill({ response })
  })
  await workbench(page)
  const draft = page.getByRole('textbox', { name: '继续完善你的需求' })
  await draft.fill('仅测试恢复：保留待办功能，新增筛选')
  await page.getByRole('button', { name: '修改应用', exact: true }).click()
  const retry = page.getByRole('button', { name: '重试原提交' })
  await expect(retry).toBeEnabled()
  expect(requests).toHaveLength(1)
  await page.reload()
  await expect(retry).toBeEnabled()
  expect(requests).toHaveLength(1)
  const panel = page.getByRole('region', { name: '提交恢复' })
  await panel.getByText('查看原提交内容').click()
  await expect(panel).toContainText('仅测试恢复：保留待办功能，新增筛选')
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    if (width < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '对话', exact: true })
        .click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (
        await new AxeBuilder({ page })
          .include('.recovery-panel')
          .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
          .analyze()
      ).violations,
    ).toEqual([])
    await panel.screenshot({ path: testInfo.outputPath(`recovery-${width}.png`) })
  }
  // The approved duplicate-tab case: browser clones sessionStorage through its opener.
  const opened = page.waitForEvent('popup')
  await page.evaluate(() => {
    window.open(location.href, '_blank')
  })
  const copy = await opened
  copy.on('pageerror', (error) => faults.push(error.message))
  copy.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) faults.push(message.text())
  })
  await expect(copy.getByRole('button', { name: '重试原提交' })).toBeEnabled()
  expect(requests).toHaveLength(1)
  await retry.focus()
  await page.keyboard.press('Enter')
  await expect(retry).toHaveCount(0)
  await copy.getByRole('button', { name: '重试原提交' }).click()
  await expect(copy.getByRole('button', { name: '重试原提交' })).toHaveCount(0)
  expect(requests).toHaveLength(3)
  expect(requests[1]).toEqual(requests[0])
  expect(requests[2]).toEqual(requests[0])
  const history = await (await page.request.get(runsUrl)).json()
  expect(history.items).toHaveLength(1)
  expect(history.items[0].id).toBe(requests[0]!.runId)
  expect(await page.evaluate((key) => sessionStorage.getItem(key), cacheKey)).not.toContain(
    '仅测试恢复',
  )
  await copy.close()
  await page.getByRole('button', { name: '取消生成', exact: true }).click()
  await expect(page.getByRole('button', { name: '取消生成', exact: true })).toHaveCount(0)
  expect(faults).toEqual([])
})

for (const mode of ['expired', 'corrupt', 'unreadable'] as const)
  test(`${mode} cache needs explicit discard and neither reload nor discard submits a run`, async ({
    page,
  }) => {
    let submissions = 0
    await page.route('**/api/generation', (route) =>
      route.fulfill({
        json: { configured: true, workerEnabled: true, remainingRequests: 2, model: 'test-only' },
      }),
    )
    await page.route(/\/api\/projects\/[^/]+\/runs(?:\?.*)?$/, (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      submissions++
      return route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
    })
    await workbench(page)
    await page
      .getByRole('textbox', { name: '继续完善你的需求' })
      .fill('过期后不得继续缓存的测试需求')
    await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '修改应用', exact: true }).click()
    await expect(page.getByRole('button', { name: '重试原提交' })).toBeVisible()
    // Alter only our synthetic recovery timestamp; no session credentials are inspected.
    if (mode === 'unreadable')
      await page.addInitScript((key) => {
        const original = Storage.prototype.getItem
        Storage.prototype.getItem = function (name) {
          if (name === key) throw new DOMException('Test storage denied', 'SecurityError')
          return original.call(this, name)
        }
      }, cacheKey)
    else
      await page.evaluate(
        ({ key, mode }) => {
          if (mode === 'corrupt') {
            sessionStorage.setItem(key, '{')
            return
          }
          const journal = JSON.parse(sessionStorage.getItem(key)!)
          journal.entries[0].createdAt = Date.now() - 24 * 60 * 60 * 1000
          sessionStorage.setItem(key, JSON.stringify(journal))
        },
        { key: cacheKey, mode },
      )
    await page.reload()
    await expect(page.getByText(/恢复记录已过期、损坏或无法读取/)).toBeVisible()
    expect(submissions).toBe(1)
    if (mode !== 'unreadable')
      expect(await page.evaluate((key) => sessionStorage.getItem(key), cacheKey)).not.toContain(
        '过期后不得继续缓存',
      )
    await expect(page.getByRole('button', { name: '修改应用', exact: true })).toBeDisabled()
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('不会取消后台任务')
      await dialog.dismiss()
    })
    await page.getByRole('button', { name: '放弃本地恢复记录' }).click()
    await expect(page.getByText(/恢复记录已过期、损坏或无法读取/)).toBeVisible()
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '放弃本地恢复记录' }).click()
    await expect(page.getByRole('button', { name: '放弃本地恢复记录' })).toHaveCount(0)
    if (mode === 'unreadable') {
      await expect(page.getByText(/刷新后无法保证恢复/)).toBeVisible()
      await expect(page.getByText(/仍可手动重试/)).toHaveCount(0)
    }
    await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toHaveValue(
      '过期后不得继续缓存的测试需求',
    )
    await expect(page.getByRole('button', { name: '修改应用', exact: true })).toBeEnabled()
    expect(submissions).toBe(1)
  })

test('guest upgrade and logout remove recovery text, including an opener copy', async ({
  page,
  context,
}) => {
  let submissions = 0
  await context.route('**/api/generation', (route) =>
    route.fulfill({
      json: { configured: true, workerEnabled: true, remainingRequests: 2, model: 'test-only' },
    }),
  )
  await context.route(/\/api\/projects\/[^/]+\/runs(?:\?.*)?$/, (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    submissions++
    return route.fulfill({ status: 200, contentType: 'application/json', body: 'invalid-json' })
  })
  await workbench(page)
  const projectUrl = page.url()
  const original = '旧访客恢复正文不可带入登录身份'
  await page.getByRole('textbox', { name: '继续完善你的需求' }).fill(original)
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '修改应用', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试原提交' })).toBeEnabled()
  const opened = page.waitForEvent('popup')
  await page.evaluate(() => {
    window.open(location.href, '_blank')
  })
  const copy = await opened
  await expect(copy.getByRole('button', { name: '重试原提交' })).toBeEnabled()
  await page.getByRole('link', { name: '邮箱注册 / 登录' }).click()
  await page
    .getByRole('textbox', { name: '邮箱地址', exact: true })
    .fill(`${crypto.randomUUID()}@example.com`)
  const challengeResponse = page.waitForResponse('**/api/auth/email/challenges')
  await page.getByRole('button', { name: '发送验证码', exact: true }).click()
  const challenge = await challengeResponse
  expect(challenge.status()).toBe(201)
  const { challengeId } = await challenge.json()
  // Test-only captured delivery, never SMTP. Do not log or export the code.
  const captured = await page.request.get(`/api/test/email-codes/${challengeId}`)
  expect(captured.status()).toBe(200)
  await page.getByRole('textbox', { name: '6 位验证码' }).fill((await captured.json()).code)
  await page.getByRole('button', { name: '验证并登录', exact: true }).click()
  await expect(page).toHaveURL(projectUrl)
  await expect(page.getByRole('link', { name: '管理账号', exact: true })).toBeVisible()
  await expect(copy.getByRole('link', { name: '管理账号' })).toBeVisible()
  for (const tab of [page, copy]) {
    const journal = await tab.evaluate((key) => sessionStorage.getItem(key), cacheKey)
    expect(JSON.parse(journal ?? '{"entries":[]}').entries).toEqual([])
    await expect(tab.getByRole('button', { name: '重试原提交' })).toHaveCount(0)
  }
  await expect(page.getByRole('textbox', { name: '继续完善你的需求' })).toHaveValue(original)
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '继续完善你的需求' }).fill('账号退出前的恢复正文')
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '修改应用', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试原提交' })).toBeEnabled()
  await page.getByRole('link', { name: '管理账号' }).click()
  await page.getByRole('button', { name: '退出账号', exact: true }).click()
  await expect(page).toHaveURL((url) => url.pathname === '/create')
  await expect(page.locator('.sidebar')).toBeVisible()
  await page.getByRole('link', { name: '邮箱注册 / 登录', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  const journal = await page.evaluate((key) => sessionStorage.getItem(key), cacheKey)
  expect(JSON.parse(journal ?? '{"entries":[]}').entries).toEqual([])
  await expect(copy.getByRole('link', { name: '邮箱注册 / 登录' })).toBeVisible()
  expect(submissions).toBe(2)
  await copy.close()
})
