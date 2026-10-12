import { expect, test, type Page } from '@playwright/test'

async function ready(page: Page) {
  await page.goto('http://127.0.0.1:5273/')
  await expect(page.frameLocator('iframe').locator('#count')).toBeEnabled()
  await expect(page.frameLocator('iframe').getByLabel('新待办')).toBeEnabled()
}
async function add(page: Page, text: string) {
  const frame = page.frameLocator('iframe')
  await frame.getByLabel('新待办').fill(text)
  await frame.getByRole('button', { name: '添加待办', exact: true }).click()
}

test('todo persists across reload and is isolated from a second visitor', async ({
  page,
  browser,
}, info) => {
  await ready(page)
  await add(page, '独立数据库中的待办')
  await expect(page.getByRole('status').filter({ hasText: '已保存到数据库' })).toBeVisible()
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText(
    '独立数据库中的待办完成',
  )
  await page.reload()
  const frame = page.frameLocator('iframe')
  await expect(frame.getByRole('listitem')).toHaveText('独立数据库中的待办完成')
  await frame.getByRole('button', { name: '完成', exact: true }).click()
  await expect(frame.getByRole('listitem')).toHaveText('独立数据库中的待办恢复')
  await page.reload()
  await expect(frame.getByRole('button', { name: '恢复', exact: true })).toBeVisible()
  const other = await browser.newContext()
  try {
    const second = await other.newPage()
    await ready(second)
    await expect(second.frameLocator('iframe').getByRole('listitem')).toHaveCount(0)
    await add(second, '另一位访客')
    await expect(second.frameLocator('iframe').getByRole('listitem')).toHaveText('另一位访客完成')
    await page.reload()
    await expect(frame.getByRole('listitem')).toHaveText('独立数据库中的待办恢复')
    await page.screenshot({ path: info.outputPath('persisted-todo.png'), fullPage: true })
  } finally {
    await other.close()
  }
})

for (const fault of ['lost', 'invalid', 'slow'] as const) {
  test(`committed ${fault} response stays unknown and explicit retry replays exactly one write`, async ({
    page,
  }, info) => {
    await ready(page)
    const requests: { key: string | undefined; body: string | null }[] = []
    page.on('request', (request) => {
      if (request.method() === 'PUT' && request.url().endsWith('/api/state'))
        requests.push({ key: request.headers()['idempotency-key'], body: request.postData() })
    })
    let release!: () => void
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    let intercepted = false
    await page.route('**/api/state', async (route) => {
      if (route.request().method() !== 'PUT' || intercepted) return route.continue()
      intercepted = true
      const actual = await route.fetch()
      expect(actual.status()).toBe(200) // The real server committed before the injected transport fault.
      if (fault === 'lost') return route.abort('connectionreset')
      if (fault === 'invalid')
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: '{"invalid":true}',
        })
      await held
      await route.abort('timedout')
    })
    try {
      await add(page, '只能创建一次')
      await expect(page.getByRole('alert')).toContainText('保存结果尚未确认', { timeout: 12000 })
      await expect(page.getByText('重新加载会丢失待确认操作的重试信息')).toBeVisible()
      const frame = page.frameLocator('iframe')
      await expect(frame.getByRole('listitem')).toHaveCount(0)
      await expect(frame.getByRole('button', { name: '添加待办', exact: true })).toBeDisabled()
      expect(requests).toHaveLength(1)
      if (fault === 'lost')
        await page.screenshot({ path: info.outputPath('unknown-save.png'), fullPage: true })
      release()
      await frame.getByRole('button', { name: '重试原操作', exact: true }).click()
      await expect(page.getByRole('status').filter({ hasText: '已保存到数据库' })).toBeVisible()
      expect(requests).toHaveLength(2)
      expect(requests[0]!.key).toMatch(/^[a-f0-9-]{36}:[a-f0-9-]{36}$/)
      expect(requests[1]).toEqual(requests[0])
      const state = await page.context().request.get('http://127.0.0.1:5273/api/state')
      expect((await state.json()).revision).toBe(1)
      await page.reload()
      await expect(frame.getByRole('listitem')).toHaveText('只能创建一次完成')
    } finally {
      release()
    }
  })
}

test('two tabs conflict without overwriting, keep draft, then allow explicit latest read', async ({
  page,
  context,
}) => {
  await ready(page)
  const other = await context.newPage()
  await ready(other)
  await add(page, '先提交')
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText('先提交完成')
  await add(other, '保留输入')
  await expect(other.getByRole('alert')).toContainText('数据已更新')
  await expect(other.frameLocator('iframe').getByLabel('新待办')).toHaveValue('保留输入')
  await other
    .frameLocator('iframe')
    .getByRole('button', { name: '读取最新数据', exact: true })
    .click()
  await expect(other.frameLocator('iframe').getByRole('listitem')).toHaveText('先提交完成')
  await expect(other.frameLocator('iframe').getByLabel('新待办')).toHaveValue('保留输入')
  await other.frameLocator('iframe').getByRole('button', { name: '添加待办', exact: true }).click()
  await expect(other.frameLocator('iframe').getByRole('listitem')).toHaveCount(2)
  await page.reload()
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveCount(2)
})
