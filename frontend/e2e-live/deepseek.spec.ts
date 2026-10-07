import { expect, test, type Page } from '@playwright/test'

async function generate(page: Page, action: string) {
  const response = page.waitForResponse(
    (reply) => reply.request().method() === 'POST' && /\/runs$/.test(reply.url()),
  )
  await page.getByRole('button', { name: action, exact: true }).click()
  const accepted = await response
  expect(accepted.status()).toBe(202)
  const { runId } = await accepted.json()
  let previous = ''
  await expect
    .poll(
      async () => {
        const reply = await page.request.get(`/api/runs/${runId}`)
        expect(reply.ok()).toBe(true)
        const run = await reply.json()
        const progress = `${run.status}/${run.stage}`
        if (previous !== progress) {
          console.log(JSON.stringify({ runId, progress, errorCode: run.errorCode }))
          previous = progress
        }
        if (['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.status))
          throw new Error(`Generation stopped: ${run.errorCode}: ${run.errorMessage}`)
        return run.status
      },
      { timeout: 370000, intervals: [1500, 3000, 5000] },
    )
    .toBe('SUCCEEDED')
  await expect(page.getByText('应用已启动', { exact: true })).toBeVisible()
  return runId as string
}

test('real DeepSeek creates, persists and modifies a working todo app', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  const prompt =
    '创建一个简洁美观的中文待办清单网页。最初没有任务。任务输入框的可访问名称为“任务名称”，提交按钮文字为“添加任务”。每项任务有一个复选框，复选框的可访问名称就是任务文本，可以勾选完成。新增和完成状态必须保存，刷新后保留。暂时不需要筛选。'
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  const readiness = await page.request.get('/api/generation')
  expect(readiness.ok()).toBe(true)
  const settings = await readiness.json()
  expect(settings.configured).toBe(true)
  expect(settings.workerEnabled).toBe(true)
  expect(settings.remainingRequests).toBeGreaterThanOrEqual(4)
  expect(settings.remainingRequests).toBeLessThanOrEqual(6)
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill(prompt)
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
  console.log(`Live acceptance project: ${page.url()}`)
  await generate(page, '生成应用')
  const frame = page.frameLocator('iframe')
  await frame.getByRole('textbox', { name: '任务名称', exact: true }).fill('真实生成验收任务')
  const saved = page.waitForResponse(
    (reply) => reply.request().method() === 'PUT' && /\/preview-state$/.test(reply.url()),
  )
  await frame.getByRole('button', { name: '添加任务', exact: true }).click()
  expect((await saved).status()).toBe(200)
  const completed = page.waitForResponse(
    (reply) => reply.request().method() === 'PUT' && /\/preview-state$/.test(reply.url()),
  )
  await frame.getByRole('checkbox', { name: '真实生成验收任务', exact: true }).check()
  expect((await completed).status()).toBe(200)
  await page.reload()
  await expect(frame.getByRole('checkbox', { name: '真实生成验收任务', exact: true })).toBeChecked()
  await page.screenshot({ path: testInfo.outputPath('created-and-persisted.png'), fullPage: true })
  await page
    .getByRole('textbox', { name: '继续完善你的需求' })
    .fill(
      '保留现有任务、完成状态、添加与完成操作以及所有可访问名称。增加两个筛选按钮，文字分别为“仅看未完成”和“显示全部”。点击前者只显示未完成任务，后者显示所有任务。不要改变现有数据字段，也不要清空已保存任务。',
    )
  await generate(page, '修改应用')
  await expect(page.getByLabel('代码版本')).toContainText('V2')
  await expect(frame.getByRole('checkbox', { name: '真实生成验收任务', exact: true })).toBeChecked()
  await frame.getByRole('button', { name: '仅看未完成', exact: true }).click()
  await expect(frame.getByRole('checkbox', { name: '真实生成验收任务', exact: true })).toHaveCount(
    0,
  )
  await frame.getByRole('button', { name: '显示全部', exact: true }).click()
  await expect(frame.getByRole('checkbox', { name: '真实生成验收任务', exact: true })).toBeChecked()
  await page.reload()
  await expect(frame.getByRole('checkbox', { name: '真实生成验收任务', exact: true })).toBeChecked()
  await page.screenshot({ path: testInfo.outputPath('modified-and-persisted.png'), fullPage: true })
  const after = await (await page.request.get('/api/generation')).json()
  console.log(
    JSON.stringify({
      requestsReservedThisTest: settings.remainingRequests - after.remainingRequests,
      remainingRequests: after.remainingRequests,
      consoleErrors: errors,
    }),
  )
  expect(errors).toEqual([])
})
