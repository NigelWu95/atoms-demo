import { test, expect, ready, add, saved, state } from './fixtures'
import { auditError, tabTo } from './accessibility'

for (const width of [320, 1440]) {
  test.describe(`${width}px error recovery`, () => {
    test.use({ viewport: { width, height: 900 } })

    test('keyboard resolves a real conflict without losing the draft', async ({
      page,
      context,
      runtime,
    }, info) => {
      const guest = await ready(page)
      const winner = await context.newPage()
      await ready(winner)
      await add(winner, '另一标签已保存')
      await saved(winner)
      await winner.close()
      await page.bringToFront()
      const frame = page.frameLocator('iframe')
      const input = frame.getByLabel('新待办', { exact: true })
      await tabTo(page, input)
      await page.keyboard.insertText('键盘保留草稿')
      await tabTo(page, frame.getByRole('button', { name: '添加待办', exact: true }))
      await page.keyboard.press('Enter')
      await expect(page.getByRole('alert')).toContainText('数据已更新')
      await expect(input).toHaveValue('键盘保留草稿')
      await auditError(page, info, '#latest')
      await tabTo(page, frame.getByRole('button', { name: '读取最新数据', exact: true }))
      await page.screenshot({ path: info.outputPath('conflict-keyboard.png'), fullPage: true })
      await page.keyboard.press('Enter')
      await expect(frame.getByRole('listitem')).toHaveText('另一标签已保存')
      await expect(input).toHaveValue('键盘保留草稿')
      await tabTo(page, frame.getByRole('button', { name: '添加待办', exact: true }))
      await page.keyboard.press('Enter')
      await saved(page)
      await expect(frame.getByRole('listitem')).toHaveText(['另一标签已保存', '键盘保留草稿'])
      expect(await runtime.evidence(guest)).toEqual({
        revision: 2,
        stateRows: 1,
        idempotencyRows: 2,
      })
      await tabTo(
        page,
        page.getByRole('button', { name: '重新加载应用', exact: false }),
        'Shift+Tab',
      )
    })

    test('keyboard confirms a lost committed response without duplicate data', async ({
      page,
      runtime,
    }, info) => {
      const guest = await ready(page)
      let committedStatus: number | undefined
      const intents: { key: string | undefined; body: string | null }[] = []
      await page.route('**/api/state', async (route) => {
        const request = route.request()
        if (request.method() !== 'PUT') return route.continue()
        intents.push({ key: request.headers()['idempotency-key'], body: request.postData() })
        if (intents.length > 1) return route.continue()
        const actual = await route.fetch({ maxRetries: 0, maxRedirects: 0 })
        committedStatus = actual.status()
        await route.abort('connectionreset')
      })
      const frame = page.frameLocator('iframe')
      const input = frame.getByLabel('新待办', { exact: true })
      await tabTo(page, input)
      await page.keyboard.insertText('键盘确认一次保存')
      await tabTo(page, frame.getByRole('button', { name: '添加待办', exact: true }))
      await page.keyboard.press('Enter')
      await expect(page.getByRole('alert')).toContainText('保存结果尚未确认')
      expect(committedStatus).toBe(200)
      await expect(input).toHaveValue('键盘确认一次保存')
      await expect(frame.getByRole('button', { name: '添加待办', exact: true })).toBeDisabled()
      await expect(
        page.getByText('不会撤销服务器可能已经完成的保存', { exact: false }),
      ).toBeVisible()
      const committed = await state(page)
      expect(committed.revision).toBe(1)
      await auditError(page, info, '#retry')
      await tabTo(page, frame.getByRole('button', { name: '重试保存', exact: true }))
      await page.screenshot({ path: info.outputPath('unknown-keyboard.png'), fullPage: true })
      await page.keyboard.press('Enter')
      await saved(page)
      expect(intents).toHaveLength(2)
      expect(intents[0]!.key).toMatch(/^[a-f0-9-]{36}:[a-f0-9-]{36}$/)
      expect(intents[1]).toEqual(intents[0])
      expect(await state(page)).toEqual(committed)
      expect(await runtime.evidence(guest)).toEqual({
        revision: 1,
        stateRows: 1,
        idempotencyRows: 1,
      })
      await tabTo(
        page,
        page.getByRole('button', { name: '重新加载应用', exact: false }),
        'Shift+Tab',
      )
    })

    test('keyboard retries startup after a real database disconnection', async ({
      page,
      runtime,
      baseURL,
    }, info) => {
      runtime.disconnectDatabase()
      try {
        const health = page.waitForResponse('**/api/health')
        await page.goto('/')
        expect((await health).status()).toBe(503)
        await expect(page.getByRole('alert')).toContainText('应用服务暂不可用')
        await expect(page.getByRole('status', { name: '服务连接状态' })).toContainText(
          '暂时无法连接',
        )
        await auditError(page, info, 'none')
        await tabTo(page, page.getByRole('button', { name: '重试连接', exact: false }))
        await page.screenshot({ path: info.outputPath('offline-keyboard.png'), fullPage: true })
      } finally {
        runtime.reconnectDatabase()
      }
      await expect
        .poll(async () => (await page.request.get(baseURL + '/api/health')).status())
        .toBe(200)
      await page.keyboard.press('Enter')
      await expect(page.frameLocator('iframe').locator('#status')).toHaveText('数据已读取。')
      await expect(page.getByRole('alert')).toHaveCount(0)
      await expect(page.getByRole('button', { name: '重新加载应用', exact: false })).toBeFocused()
      expect((await state(page)).revision).toBe(0)
    })
  })
}
