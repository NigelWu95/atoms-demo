import { fileURLToPath } from 'node:url'
import { test, expect, ready, add, saved, state } from './fixtures'

test('real sandbox rejects host access and network capabilities; self navigation closes the bridge', async ({
  page,
  context,
  baseURL,
  runtime,
}, info) => {
  const escaped: string[] = []
  const errors: string[] = []
  context.on('weberror', (event) => errors.push(event.error().name))
  // Defense in depth for the test itself: even a broken CSP cannot contact another service.
  await context.route('**/security-probe/**', async (route) => {
    escaped.push(new URL(route.request().url()).pathname)
    await route.abort('blockedbyclient')
  })
  const guest = await ready(page)
  const before = await state(page)
  const child = page.frames().find((frame) => frame !== page.mainFrame())
  if (!child) throw new Error('Expected the real application frame.')
  await expect(page.locator('iframe')).toHaveAttribute('sandbox', 'allow-scripts')
  // Inline script executes under the actual document policy, not a forged document response.
  // https://playwright.dev/docs/api/class-frame#frame-add-script-tag
  await child.addScriptTag({ path: fileURLToPath(new URL('./sandbox-probe.js', import.meta.url)) })
  const frame = page.frameLocator('iframe')
  await frame.getByRole('button', { name: '运行安全探针', exact: true }).click()
  await expect
    .poll(async () => {
      const text = await frame.locator('#security-results').innerText()
      return text ? JSON.parse(text) : null
    })
    .toEqual({
      origin: 'null',
      parent: 'SecurityError',
      network: 'TypeError',
      script: 'blocked',
      worker: 'blocked',
      violations: ['connect-src', 'script-src-elem', 'worker-src'],
    })
  await frame.getByRole('button', { name: '测试宿主导航边界', exact: true }).click()
  await expect(frame.locator('#probe-top')).toHaveAttribute('data-result', 'SecurityError')
  await frame.getByRole('button', { name: '测试弹窗边界', exact: true }).click()
  await expect(frame.locator('#probe-popup')).toHaveAttribute('data-result', 'blocked')
  expect(context.pages()).toHaveLength(1)
  expect(page.url()).toBe(baseURL + '/')
  expect(await state(page)).toEqual(before)
  expect(escaped).toEqual([])
  await add(page, '安全探针后正常保存')
  await saved(page)
  await frame.getByRole('button', { name: '测试应用自身导航', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('应用发生导航，旧连接已关闭')
  await expect(page.locator('iframe')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('navigation-closed.png'), fullPage: true })
  let confirmation = ''
  page.once('dialog', async (dialog) => {
    confirmation = dialog.message()
    await dialog.accept()
  })
  await page.getByRole('button', { name: '重新加载应用', exact: false }).press('Enter')
  expect(confirmation).toContain('不会撤销服务器可能已经完成的保存')
  await expect(frame.getByRole('listitem')).toHaveText('安全探针后正常保存')
  expect(await runtime.evidence(guest)).toEqual({ revision: 1, stateRows: 1, idempotencyRows: 1 })
  expect(escaped).toEqual([])
  expect(errors).toEqual([])
})

test('direct runtime documents remain opaque while the shell and API keep strict policies', async ({
  page,
  context,
  baseURL,
}) => {
  await ready(page)
  const direct = await context.newPage()
  const response = await direct.goto(baseURL + '/api/app/document')
  expect(response?.status()).toBe(200)
  expect(response?.headers()['content-security-policy']).toContain('sandbox allow-scripts;')
  expect(response?.headers()['x-frame-options']).toBe('SAMEORIGIN')
  expect(await direct.evaluate(() => window.origin)).toBe('null')
  await expect(direct.getByRole('button', { name: '添加待办', exact: true })).toBeDisabled()
  for (const path of ['/', '/api/app']) {
    const actual = await page.request.get(baseURL + path)
    expect(actual.status()).toBe(200)
    expect(actual.headers()['content-security-policy']).not.toContain('unsafe-inline')
    expect(actual.headers()['x-frame-options']).toBe('DENY')
  }
  await direct.close()
})
