import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [320, 390, 768, 1024, 1440]) {
  test(`real service readiness, keyboard and accessibility at ${width}px`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const failures: string[] = []
    page.on('pageerror', (error) => failures.push(error.name))
    page.on('console', (message) => {
      if (message.type() === 'error' || message.type() === 'warning')
        failures.push(`${message.text().slice(0, 600)} (${message.location().url.split('?')[0]})`)
    })
    const health = page.waitForResponse('**/api/health')
    const navigation = await page.goto('/')
    expect(navigation?.headers()['content-security-policy']).toContain("script-src 'self';")
    const response = await health
    expect(response.status()).toBe(200)
    expect(await response.json()).toEqual({ status: 'UP' })
    expect(response.headers()['set-cookie']).toBeUndefined()
    await expect(page.getByRole('status')).toContainText('服务连接正常')
    await expect(page.getByRole('heading', { name: '应用界面尚未接入' })).toBeVisible()
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true)
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('main')).toBeFocused()
    await page.keyboard.press('Tab')
    const button = page.getByRole('button', { name: '重新检查连接' })
    await expect(button).toBeFocused()
    const checkAgain = page.waitForResponse('**/api/health')
    await page.keyboard.press('Space')
    expect((await checkAgain).status()).toBe(200)
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
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('status')).toContainText('服务连接正常')
  await expect(page.getByRole('button', { name: '重新检查连接' })).toBeFocused()
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
  await expect(page.getByRole('status')).toContainText('服务连接正常')
  expect(errors).toEqual([])
})
