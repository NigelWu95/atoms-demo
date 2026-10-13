import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function openHistory(page: Page) {
  await page.goto('http://127.0.0.1:8081/test-lab')
  await page.getByLabel('测试产物').selectOption('todo-history')
  await page.getByRole('button', { name: '装载测试产物' }).click()
  await page.getByRole('link', { name: '打开测试工作台' }).click()
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '下载预览工程 ZIP', exact: true })).toBeDisabled()
  await page.getByText('其他导出：版本源码（未绑定运行预览）', { exact: true }).click()
  return page.getByRole('region', { name: '版本源码导出' })
}

test('downloads real current and historical engineering ZIPs with keyboard access', async ({
  page,
}, info) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['warning', 'error'].includes(message.type())) errors.push(message.text())
  })
  const panel = await openHistory(page)
  const frame = page.frameLocator('iframe')
  await frame.getByLabel('新待办').fill('PRIVATE_STATE_MUST_NOT_BE_EXPORTED')
  await frame.getByRole('button', { name: '添加待办' }).click()
  await expect(page.getByText('已保存到数据库', { exact: true })).toBeVisible()
  const projectId = new URL(page.url()).pathname.split('/').at(-1)!
  const projectPath = `http://127.0.0.1:5174/api/projects/${projectId}`
  const before = await (await page.request.get(projectPath)).json()
  for (const number of [2, 1]) {
    if (number === 1)
      await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V1 · 历史只读' })
    await expect(panel).toContainText(`选中 V${number}`)
    const versionId = await page.getByLabel('代码版本', { exact: true }).inputValue()
    const button = panel.getByRole('button', { name: '导出版本源码 ZIP', exact: true })
    await button.focus()
    await page.keyboard.press('Tab')
    await page.keyboard.press('Shift+Tab')
    await expect(button).toBeFocused()
    const event = page.waitForEvent('download')
    await page.keyboard.press('Enter')
    const download = await event
    expect(download.suggestedFilename()).toBe(`atoms-${projectId}-v${number}-engineering.zip`)
    const path = info.outputPath(
      number === 2 ? 'current-engineering.zip' : 'history-engineering.zip',
    )
    await download.saveAs(path)
    expect(await download.failure()).toBeNull()
    const bytes = await readFile(path)
    expect(bytes.length).toBeLessThan(9 * 1024 * 1024)
    expect(bytes.subarray(0, 4)).toEqual(Buffer.from([80, 75, 3, 4]))
    // Only reads our fixed fixture download; no shell or archive extraction/execution here.
    const manifest = JSON.parse(
      execFileSync('unzip', ['-p', path, 'bundle-manifest.json'], {
        maxBuffer: 1024 * 1024,
      }).toString(),
    )
    const application = JSON.parse(
      execFileSync('unzip', ['-p', path, 'application/manifest.json']).toString(),
    )
    expect(manifest.sourceVersionId).toBe(versionId)
    expect(manifest.dataMode).toBe('INITIAL_ONLY')
    expect(manifest.files).toHaveLength(110)
    expect(application.initialState).toEqual({ todos: [] })
    expect(bytes.includes(Buffer.from('PRIVATE_STATE_MUST_NOT_BE_EXPORTED'))).toBe(false)
    await expect(panel.getByRole('status')).toContainText(`V${number} 工程已交给浏览器下载`)
  }
  expect(await (await page.request.get(projectPath)).json()).toEqual(before)
  expect(errors).toEqual([])
})

// Audit each width separately: download integrity and five complete axe scans
// should not share one 30s lifecycle budget. No timeout or rule is relaxed.
for (const width of [320, 390, 768, 1024, 1440]) {
  test(`historical engineering download remains accessible at ${width}px`, async ({
    page,
  }, info) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (['warning', 'error'].includes(message.type())) errors.push(message.text())
    })
    const panel = await openHistory(page)
    await page.getByLabel('代码版本', { exact: true }).selectOption({ label: 'V1 · 历史只读' })
    const event = page.waitForEvent('download')
    await panel.getByRole('button', { name: '导出版本源码 ZIP', exact: true }).click()
    expect(await (await event).failure()).toBeNull()
    await expect(panel.getByRole('status')).toContainText('V1 工程已交给浏览器下载')
    await page.setViewportSize({ width, height: 1000 })
    if (width < 768)
      await page
        .getByRole('group', { name: '工作台区域' })
        .getByRole('button', { name: '预览', exact: true })
        .click()
    await expect(panel.getByRole('button')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    await page.screenshot({ path: info.outputPath(`engineering-${width}.png`), fullPage: true })
    expect(errors).toEqual([])
  })
}

test('download errors retain the draft and preview and allow an explicit retry', async ({
  page,
}) => {
  const panel = await openHistory(page)
  const frame = page.frameLocator('iframe')
  await frame.getByLabel('新待办').fill('下载失败保留输入')
  await page.route(
    '**/engineering.zip',
    (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'EXPORT_BUSY', message: '工程下载繁忙，请稍后重试。' },
          requestId: 'export-test',
        }),
      }),
    { times: 1 },
  )
  await panel.getByRole('button').click()
  await expect(panel.getByRole('alert')).toContainText('export-test')
  await expect(frame.getByLabel('新待办')).toHaveValue('下载失败保留输入')
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  const event = page.waitForEvent('download')
  await panel.getByRole('button', { name: '重试导出版本源码 ZIP' }).click()
  expect(await (await event).failure()).toBeNull()
  await expect(panel.getByRole('alert')).toHaveCount(0)
})
