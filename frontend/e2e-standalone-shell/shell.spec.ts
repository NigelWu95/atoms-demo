import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [320, 390, 768, 1024, 1440]) {
  test(`real session, opaque app, keyboard and accessibility at ${width}px`, async ({
    page,
    context,
  }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const failures: string[] = []
    page.on('pageerror', (error) => failures.push(error.name))
    page.on('console', (message) => {
      if (message.type() === 'error' || message.type() === 'warning')
        failures.push(`${message.text().slice(0, 600)} (${message.location().url.split('?')[0]})`)
    })
    const health = page.waitForResponse('**/api/health')
    const session = page.waitForResponse('**/api/session')
    const documentLoaded = page.waitForResponse('**/api/app/document')
    const navigation = await page.goto('/')
    expect(navigation?.headers()['content-security-policy']).toContain("script-src 'self';")
    const response = await health
    expect(response.status()).toBe(200)
    expect(await response.json()).toEqual({ status: 'UP' })
    expect(response.headers()['set-cookie']).toBeUndefined()
    const identity = await (await session).json()
    expect(identity.isGuest).toBe(true)
    const appDocument = await documentLoaded
    expect(appDocument.status()).toBe(200)
    expect(appDocument.headers()['content-security-policy']).toContain('sandbox allow-scripts')
    expect(appDocument.headers()['x-frame-options']).toBe('SAMEORIGIN')
    await expect(page.getByRole('status', { name: '服务连接状态' })).toContainText('应用信息已就绪')
    await expect(page.getByRole('heading', { name: '计数器', exact: true })).toBeVisible()
    const frame = page.frameLocator('iframe')
    const localCount = frame.getByRole('button', { name: '本页计数：0' })
    await localCount.click()
    await expect(frame.getByRole('button', { name: '本页计数：1' })).toBeVisible()
    await frame.getByRole('button', { name: '本页计数：1' }).press('Space')
    await expect(frame.getByRole('button', { name: '本页计数：2' })).toBeVisible()
    await expect(frame.locator('#count')).toBeDisabled()
    await expect(page.getByRole('alert')).toContainText('不能读取或保存')
    expect(await page.locator('iframe').getAttribute('sandbox')).toBe('allow-scripts')
    const child = page.frames().find((entry) => entry !== page.mainFrame())!
    expect(await child.evaluate(() => window.origin)).toBe('null')
    expect(
      await child.evaluate(() => {
        try {
          return parent.document.title
        } catch (error) {
          return (error as Error).name
        }
      }),
    ).toBe('SecurityError')
    expect(
      await child.evaluate(() => {
        try {
          return window.localStorage.length
        } catch (error) {
          return (error as Error).name
        }
      }),
    ).toBe('SecurityError')
    const cookies = await context.cookies()
    expect(cookies).toHaveLength(1)
    expect(cookies[0]).toMatchObject({ httpOnly: true, sameSite: 'Lax' })
    expect(
      await page.evaluate(() => ({
        cookie: document.cookie,
        local: localStorage.length,
        session: sessionStorage.length,
      })),
    ).toEqual({ cookie: '', local: 0, session: 0 })
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true)
    await page.getByRole('link', { name: '跳到主要内容' }).focus()
    await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('main')).toBeFocused()
    await page.keyboard.press('Tab')
    const button = page.getByRole('button', { name: '重新加载应用' })
    await expect(button).toBeFocused()
    const oldChannel = await page.locator('iframe').getAttribute('src')
    page.once('dialog', (dialog) => dialog.dismiss())
    await page.keyboard.press('Space')
    await expect(frame.getByRole('button', { name: '本页计数：2' })).toBeVisible()
    expect(await page.locator('iframe').getAttribute('src')).toBe(oldChannel)
    const nextSession = page.waitForResponse('**/api/session')
    page.once('dialog', (dialog) => dialog.accept())
    await page.keyboard.press('Space')
    expect(await (await nextSession).json()).toEqual(identity)
    await expect(frame.getByRole('button', { name: '本页计数：0' })).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('不能读取或保存')
    expect(await page.locator('iframe').getAttribute('src')).not.toBe(oldChannel)
    await expect(button).toBeFocused()
    const box = await button.boundingBox()
    expect(box?.height).toBeGreaterThanOrEqual(44)
    const audit = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze()
    expect(audit.violations).toEqual([])
    expect(failures).toEqual([])
    if (width === 320 || width === 1440)
      await page.screenshot({ path: info.outputPath(`startup-${width}.png`), fullPage: true })
  })
}

test('offline error remains visible during keyboard retry, then returns to real service', async ({
  page,
}, info) => {
  await page.route('**/api/health', (route) => route.abort('internetdisconnected'), { times: 1 })
  await page.goto('/')
  await expect(page.getByRole('alert')).toContainText('无法连接应用服务')
  await page.screenshot({ path: info.outputPath('startup-error.png'), fullPage: true })
  let release!: () => void
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route(
    '**/api/health',
    async (route) => {
      await held
      await route.continue()
    },
    { times: 1 },
  )
  const button = page.getByRole('button', { name: '重试连接' })
  await button.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('status')).toContainText('正在连接服务')
  await expect(page.getByRole('alert')).toContainText('无法连接应用服务')
  const response = page.waitForResponse('**/api/health')
  release()
  expect((await response).status()).toBe(200)
  await expect(page.getByRole('status', { name: '服务连接状态' })).toContainText('应用信息已就绪')
  await expect(page.getByRole('alert')).toContainText('不能读取或保存')
  await expect(page.getByRole('alert')).not.toContainText('无法连接应用服务')
  await expect(page.getByRole('button', { name: '重新加载应用' })).toBeFocused()
})

test('dev and built preview preserve origin and API security boundaries', async ({
  request,
  page,
}) => {
  for (const origin of ['http://127.0.0.1:5273', 'http://127.0.0.1:4273']) {
    const health = await request.get(`${origin}/api/health`, { headers: { Origin: origin } })
    expect(health.status()).toBe(200)
    expect(health.headers()['x-frame-options']).toBe('DENY')
    expect(health.headers()['access-control-allow-origin']).toBeUndefined()
    const denied = await request.post(`${origin}/api/session`, {
      headers: { Origin: 'https://evil.test' },
      data: {},
    })
    expect(denied.status()).toBe(403)
    const forged = await request.get(`${origin}/api/health`, { headers: { Host: 'evil.test' } })
    expect(forged.status()).toBe(403)
    const crossSite = await request.get(`${origin}/api/health`, {
      headers: { 'Sec-Fetch-Site': 'cross-site' },
    })
    expect(crossSite.status()).toBe(403)
    const unknown = await request.get(`${origin}/api/not-a-route`)
    expect(unknown.status()).toBe(404)
    expect(unknown.headers()['content-type']).toContain('application/json')
    const csrfMissing = await request.post(`${origin}/api/session`, {
      headers: { Origin: origin },
      data: {},
    })
    expect(csrfMissing.status()).toBe(403)
  }
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.name))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text().slice(0, 600))
  })
  const response = await page.goto('http://127.0.0.1:4273')
  expect(response?.headers()['content-security-policy']).not.toMatch(/unsafe-inline|ws:/)
  await expect(page.getByRole('status', { name: '服务连接状态' })).toContainText('应用信息已就绪')
  await page.frameLocator('iframe').getByRole('button', { name: '本页计数：0' }).click()
  await expect(
    page.frameLocator('iframe').getByRole('button', { name: '本页计数：1' }),
  ).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('不能读取或保存')
  expect(errors).toEqual([])
})

test('expired session is explicit, has a safe request ID and requires a manual reconnect', async ({
  page,
}) => {
  let sessionCalls = 0
  page.on('request', (request) => {
    if (request.url().endsWith('/api/session')) sessionCalls++
  })
  await page.route(
    '**/api/app',
    (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'SESSION_REQUIRED', message: 'PRIVATE_UPSTREAM_TEXT', retryable: false },
          requestId: '9972c85c-074e-48f8-a692-c2d4c20f2122',
        }),
      }),
    { times: 1 },
  )
  await page.goto('/')
  await expect(page.getByRole('alert')).toContainText('访客会话已失效')
  await expect(page.getByText('请求编号：9972c85c-074e-48f8-a692-c2d4c20f2122')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('PRIVATE_UPSTREAM_TEXT')
  expect(sessionCalls).toBe(1)
  await expect(page.locator('iframe')).toHaveCount(0)
  await page.getByRole('button', { name: '重试连接' }).click()
  await expect(
    page.frameLocator('iframe').getByRole('button', { name: '本页计数：0' }),
  ).toBeVisible()
  expect(sessionCalls).toBe(2)
})

test('document handshake timeout is visible and an explicit reload creates a fresh channel', async ({
  page,
}) => {
  // Negative-only transport fixture: real auth and metadata, but no SDK hello from this document.
  await page.route(
    '**/api/app/document',
    (route) =>
      route.fulfill({
        contentType: 'text/html',
        body: '<!doctype html><html lang="zh-CN"><title>断开的沙箱</title><p>没有握手</p></html>',
      }),
    { times: 1 },
  )
  await page.goto('/')
  await expect(page.getByRole('alert')).toContainText('连接超时', { timeout: 15000 })
  const oldChannel = await page.locator('iframe').getAttribute('src')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '重新加载应用' }).click()
  await expect(
    page.frameLocator('iframe').getByRole('button', { name: '本页计数：0' }),
  ).toBeVisible()
  expect(await page.locator('iframe').getAttribute('src')).not.toBe(oldChannel)
})
