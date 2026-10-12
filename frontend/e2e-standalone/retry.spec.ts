import { test, expect, ready, add, saved, state } from './fixtures'

for (const fault of ['lost', 'invalid', 'slow'] as const) {
  test(`committed ${fault} response is confirmed by the same intent without a second write`, async ({
    page,
    runtime,
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
    // https://playwright.dev/docs/api/class-route#route-fetch
    // Forward to the actual JAR/DB before damaging delivery, never fake a committed success.
    await page.route('**/api/state', async (route) => {
      if (route.request().method() !== 'PUT' || intercepted) return route.continue()
      intercepted = true
      const actual = await route.fetch({ maxRetries: 0, maxRedirects: 0 })
      expect(actual.status()).toBe(200)
      expect((await actual.json()).revision).toBe(1)
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
      await add(page, '只提交一次')
      await expect(page.getByRole('alert')).toContainText('保存结果尚未确认', { timeout: 12000 })
      await expect(
        page.getByText('重新加载会丢失待确认操作的重试信息', { exact: false }),
      ).toBeVisible()
      const frame = page.frameLocator('iframe')
      await expect(frame.getByRole('button', { name: '添加待办', exact: true })).toBeDisabled()
      await expect(frame.getByLabel('新待办', { exact: true })).toHaveValue('只提交一次')
      await expect(page.getByRole('status').filter({ hasText: '已保存到数据库' })).toHaveCount(0)
      expect(requests).toHaveLength(1)
      const committed = await state(page)
      expect(committed.revision).toBe(1)
      expect(committed.value.todos).toHaveLength(1)
      release()
      // Replay must survive losing process memory, not just an in-memory cache.
      if (fault === 'lost') {
        await page.screenshot({ path: info.outputPath('unknown-save.png'), fullPage: true })
        await runtime.restart()
      }
      await frame.getByRole('button', { name: '重试保存', exact: true }).click()
      await saved(page)
      expect(requests).toHaveLength(2)
      expect(requests[0]!.key).toMatch(/^[a-f0-9-]{36}:[a-f0-9-]{36}$/)
      expect(requests[1]).toEqual(requests[0])
      expect(await state(page)).toEqual(committed)
      await page.reload()
      await expect(frame.getByRole('listitem')).toHaveText('只提交一次')
    } finally {
      release()
    }
  })
}
