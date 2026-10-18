import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { openTool } from '../e2e-runtime/tools'

async function openHistory(page: Page) {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-history')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  await openTool(page, '构建与进程')
  return page.getByRole('region', { name: '本机构建工程' })
}

test('real native build survives refresh; its frozen ZIP, history and responsive controls are usable', async ({
  page,
}, info) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  const panel = await openHistory(page)
  const projectId = new URL(page.url()).pathname.split('/').at(-1)!
  const base = `/api/projects/${projectId}/builds`
  const button = panel.getByRole('button', { name: '构建 V2 工程', exact: true })
  await button.focus()
  await page.keyboard.press('Tab')
  await page.keyboard.press('Shift+Tab')
  await expect(button).toBeFocused()
  const accepted = page.waitForResponse(
    (r) => r.url().endsWith(base) && r.request().method() === 'POST',
  )
  await page.keyboard.press('Enter')
  const response = await accepted
  expect(response.status()).toBe(202)
  const { buildId } = await response.json()
  await expect(panel).toContainText(buildId.slice(0, 8))
  await page.reload()
  await openTool(page, '构建与进程')
  await expect(panel).toContainText(buildId.slice(0, 8))
  await expect
    .poll(
      async () => {
        const state = await (await page.request.get(`${base}/${buildId}`)).json()
        return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(state.status)
          ? state.status
          : 'ACTIVE'
      },
      { timeout: 660000, intervals: [2000] },
    )
    .not.toBe('ACTIVE')
  const build = await (await page.request.get(`${base}/${buildId}`)).json()
  expect(build.status, build.errorCode ?? 'verified completion').toBe('SUCCEEDED')
  await expect(panel.getByRole('status').first()).toHaveText('构建完成，可启动本机应用。', {
    timeout: 15000,
  })
  const event = page.waitForEvent('download')
  await panel.getByRole('button', { name: `下载构建 ${buildId.slice(0, 8)} 的工程 ZIP` }).click()
  const download = await event
  expect(download.suggestedFilename()).toBe(`atoms-build-${buildId}-engineering.zip`)
  const archive = info.outputPath('build-engineering.zip')
  await download.saveAs(archive)
  expect(await download.failure()).toBeNull()
  expect(
    createHash('sha256')
      .update(await readFile(archive))
      .digest('hex'),
  ).toBe(build.archiveSha256)
  const report = JSON.parse(
    await readFile(
      join(process.env.WORKSPACE_BROWSER_OUTPUT!, 'builds', buildId, 'result/build.json'),
      'utf8',
    ),
  )
  expect(report.loadedRuntimeId).toBe(build.loadedRuntimeId)
  expect(report.jarSha256).toBe(build.jarSha256)
  await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V1 · 历史只读' })
  await openTool(page, '构建与进程')
  await expect(panel).toContainText('该版本尚无构建记录。')
  await expect(panel.getByRole('button', { name: '构建 V1 工程', exact: true })).toBeEnabled()
  await page.getByLabel('代码版本', { exact: true }).selectOption(build.versionId)
  await openTool(page, '构建与进程')
  await expect(panel).toContainText(buildId.slice(0, 8))
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    if (width < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览', exact: true })
        .click()
    await openTool(page, '构建与进程')
    await expect(
      panel.getByRole('button', { name: `下载构建 ${buildId.slice(0, 8)} 的工程 ZIP` }),
    ).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    await page.screenshot({ path: info.outputPath(`workspace-${width}.png`), fullPage: true })
  }
  expect(errors).toEqual([])
})

test('lost acceptance confirms the same intent; cancellation retains preview and other visitors cannot see the task', async ({
  page,
  browser,
}) => {
  const panel = await openHistory(page)
  const projectId = new URL(page.url()).pathname.split('/').at(-1)!
  const base = `/api/projects/${projectId}/builds`
  const keys: string[] = []
  let buildId = ''
  await page.route(`**${base}`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push(route.request().headers()['idempotency-key']!)
    const response = await route.fetch()
    expect(response.status()).toBe(202)
    buildId = (await response.json()).buildId
    if (keys.length === 1) await route.abort('failed')
    else await route.fulfill({ response })
  })
  await panel.getByRole('button', { name: '构建 V2 工程', exact: true }).click()
  await expect(panel.getByRole('button', { name: '确认上次构建请求' })).toBeVisible()
  await page.reload()
  await openTool(page, '构建与进程')
  await panel.getByRole('button', { name: '确认上次构建请求' }).click()
  await expect(panel).toContainText(buildId.slice(0, 8))
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
  const frame = page.frameLocator('iframe')
  await page.getByText('构建与进程', { exact: true }).press('Escape')
  await frame.getByLabel('新待办').fill('取消构建仍保留草稿')
  await openTool(page, '构建与进程')
  await panel.getByRole('button', { name: '取消构建', exact: true }).click()
  await expect(panel.getByRole('status').first()).toContainText('构建已取消', { timeout: 30000 })
  await expect(frame.getByLabel('新待办')).toHaveValue('取消构建仍保留草稿')
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  const detail = await (await page.request.get(`${base}/${buildId}`)).json()
  expect(detail.status).toBe('CANCELLED')
  const stranger = await browser.newContext()
  try {
    const second = await stranger.newPage()
    const otherPanel = await openHistory(second)
    await expect(otherPanel).toContainText('该版本尚无构建记录。')
    expect((await second.request.get(`http://127.0.0.1:5174${base}/${buildId}`)).status()).toBe(404)
  } finally {
    await stranger.close()
  }
  // A clearly injected admission error exercises the UI without corrupting live tools/configuration.
  await page.unroute(`**${base}`)
  await page.route(`**${base}`, (route) =>
    route.request().method() === 'POST'
      ? route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'BUILD_UNAVAILABLE', message: '本机构建未就绪。' },
            requestId: 'workspace-admission-fixture',
          }),
        })
      : route.continue(),
  )
  await panel.getByRole('button', { name: '构建 V2 工程', exact: true }).click()
  await expect(panel.getByRole('alert')).toBeVisible()
  await expect(frame.getByLabel('新待办')).toHaveValue('取消构建仍保留草稿')
})
