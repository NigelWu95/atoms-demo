import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function requestCode(page: Page, email: string) {
  await page.getByRole('textbox', { name: '邮箱地址', exact: true }).fill(email)
  const reply = page.waitForResponse('**/api/auth/email/challenges')
  await page.getByRole('button', { name: '发送验证码', exact: true }).click()
  const sent = await reply
  expect(sent.status()).toBe(201)
  const { challengeId } = await sent.json()
  const captured = await page.request.get(`/api/test/email-codes/${challengeId}`)
  expect(captured.status()).toBe(200)
  return (await captured.json()).code as string
}
async function verify(page: Page, code: string, destination = '/projects') {
  await page.getByRole('textbox', { name: '6 位验证码' }).fill(code)
  await page.getByRole('button', { name: '验证并登录', exact: true }).click()
  await expect(page).toHaveURL(new URL(destination, page.url()).href)
}

test('email verification survives visible-page session revalidation without resending', async ({
  page,
}, info) => {
  const errors: string[] = []
  let sends = 0
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/auth/email/challenges')) sends++
  })
  await page.goto('/account?returnTo=/')
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  const email = `${crypto.randomUUID()}@example.com`
  const code = await requestCode(page, email)
  await page.getByRole('textbox', { name: '6 位验证码' }).fill(code.slice(0, 3))
  for (let visit = 0; visit < 2; visit++) {
    // Replay the return event: Playwright keeps background tabs visible by default.
    // The production listener, session requests and verification still run unchanged.
    const checked = page.waitForResponse('**/api/me')
    await page.locator('body').dispatchEvent('visibilitychange')
    expect((await checked).status()).toBe(200)
    await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
    await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toHaveValue(email)
    await expect(page.getByRole('textbox', { name: '6 位验证码' })).toHaveValue(code.slice(0, 3))
    await expect(page.getByRole('button', { name: '验证并登录', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: /秒后可重发/ })).toBeDisabled()
  }
  await page.screenshot({ path: info.outputPath('code-after-revalidation.png'), fullPage: true })
  await verify(page, code, '/')
  await expect(page.getByRole('link', { name: '我的账号', exact: true })).toBeVisible()
  expect(sends).toBe(1)
  expect(errors).toEqual([])
})

test('email account preserves projects, synchronizes tabs, logs out and logs in from another browser', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(150000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await expect(page.getByText('访客创作者', { exact: true })).toBeVisible()
  const owner = (await (await page.request.get('/api/me')).json()).userId
  const csrf = (await (await page.request.get('/api/csrf')).json()).token
  const project = await page.request.post('/api/projects', {
    data: { name: '邮箱注册前的项目' },
    headers: { 'X-CSRF-TOKEN': csrf, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(project.status()).toBe(201)
  const projectPath = `/api/projects/${(await project.json()).id}`
  const sibling = await page.context().newPage()
  await sibling.goto('/')
  await sibling.getByRole('textbox', { name: '描述你的应用想法' }).fill('只属于旧身份的草稿')
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('登录后继续制作 2048')
  await page.getByRole('link', { name: '注册 / 登录', exact: true }).click()
  await expect(page.locator('.sidebar')).toHaveCount(0)
  const email = `${crypto.randomUUID()}@example.com`,
    sentAt = Date.now()
  const code = await requestCode(page, email)
  await expect(page.getByRole('textbox', { name: '6 位验证码' })).toBeFocused()
  await expect(page.getByRole('button', { name: /秒后可重发/ })).toBeDisabled()
  await verify(page, code, '/')
  await expect(page.getByRole('textbox', { name: '描述你的应用想法' })).toHaveValue(
    '登录后继续制作 2048',
  )
  expect((await (await page.request.get('/api/me')).json()).userId).toBe(owner)
  expect((await page.request.get(projectPath)).status()).toBe(200)
  await expect(sibling.getByRole('link', { name: '我的账号', exact: true })).toBeVisible()
  await expect(sibling.getByRole('textbox', { name: '描述你的应用想法' })).toHaveValue('')
  await page.screenshot({ path: info.outputPath('home-after-login.png'), fullPage: true })
  await page.getByRole('link', { name: '我的账号', exact: true }).click()
  await expect(page.getByText('邮箱账号 · 已验证', { exact: true })).toBeVisible()
  await expect(page.locator('.account-facts')).toContainText(email)
  await page.getByRole('button', { name: '退出账号', exact: true }).click()
  await expect(page).toHaveURL(/\/create$/)
  await expect(page.locator('.sidebar')).toBeVisible()
  await expect(sibling.getByRole('link', { name: '注册 / 登录', exact: true })).toBeVisible()
  await sibling.getByRole('link', { name: '我的项目', exact: true }).click()
  await expect(sibling.getByRole('heading', { name: '还没有项目，从一个想法开始' })).toBeVisible()
  await expect(sibling.getByRole('link', { name: /邮箱注册前的项目/ })).toHaveCount(0)
  expect((await page.request.get(projectPath)).status()).toBe(404)

  const separate = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  try {
    const login = await separate.newPage()
    await login.goto('/')
    await expect(login.getByRole('link', { name: '注册 / 登录', exact: true })).toBeVisible()
    const guestCsrf = (await (await login.request.get('/api/csrf')).json()).token
    const guestProject = await login.request.post('/api/projects', {
      data: { name: '不可合并的访客项目' },
      headers: { 'X-CSRF-TOKEN': guestCsrf, 'Idempotency-Key': crypto.randomUUID() },
    })
    expect(guestProject.status()).toBe(201)
    const guestId = (await guestProject.json()).id
    await login.goto(`/projects/${guestId}`)
    await login.getByRole('link', { name: '邮箱注册 / 登录', exact: true }).click()
    await login.reload()
    expect(new URL(login.url()).searchParams.get('returnTo')).toBe(`/projects/${guestId}`)
    await expect(login.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
    expect((await login.request.get(projectPath)).status()).toBe(404)
    // Exercise the real, fixed 60-second cooldown without any test-only bypass in production code.
    await login.waitForTimeout(Math.max(0, sentAt + 62000 - Date.now()))
    await verify(
      login,
      await requestCode(login, email),
      '/projects?notice=account-project-unavailable',
    )
    await expect(
      login.getByRole('status').filter({ hasText: '原项目不存在或不属于当前账号' }),
    ).toBeVisible()
    expect((await login.request.get(`/api/projects/${guestId}`)).status()).toBe(404)
    expect((await (await login.request.get('/api/me')).json()).userId).toBe(owner)
    expect((await login.request.get(projectPath)).status()).toBe(200)
    await login.getByRole('link', { name: '管理账号', exact: true }).click()
    await login.reload()
    await expect(login.getByText('邮箱账号 · 已验证', { exact: true })).toBeVisible()
    await expect(login.locator('.account-facts')).toContainText(email)
  } finally {
    await separate.close()
    await sibling.close()
  }
  expect(errors).toEqual([])
})

test('account form is keyboard accessible and responsive; invalid codes do not sign in', async ({
  page,
}, info) => {
  await page.goto('/account')
  await page.reload()
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  await expect(page.locator('.sidebar')).toHaveCount(0)
  await page.getByRole('textbox', { name: '邮箱地址', exact: true }).fill('layout@example.com')
  await expect(page.getByRole('button', { name: '发送验证码', exact: true })).toBeEnabled()
  for (const width of [320, 390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    await page.screenshot({ path: info.outputPath(`account-${width}.png`), fullPage: true })
  }
  const code = await requestCode(page, `${crypto.randomUUID()}@example.com`)
  await page
    .getByRole('textbox', { name: '6 位验证码' })
    .fill(code === '000000' ? '000001' : '000000')
  await page.getByRole('button', { name: '验证并登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('验证码无效')
  expect((await (await page.request.get('/api/me')).json()).isGuest).toBe(true)
  await page.getByRole('textbox', { name: '6 位验证码' }).fill(code)
  await page.getByRole('textbox', { name: '6 位验证码' }).press('Enter')
  await expect(page).toHaveURL(/\/projects$/)
  await expect(page.getByRole('link', { name: '管理账号', exact: true })).toBeVisible()
})

test('account entry supports keyboard navigation, browser back and session recovery', async ({
  page,
}) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('返回时保留的想法')
  const entry = page.getByRole('link', { name: '注册 / 登录', exact: true })
  await entry.focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/account\?returnTo=\/$/)
  await expect(page.getByRole('heading', { level: 1 })).toBeFocused()
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  await page.goBack()
  await expect(page.getByRole('textbox', { name: '描述你的应用想法' })).toHaveValue(
    '返回时保留的想法',
  )
  await page.route('**/api/session', (route) => route.fulfill({ status: 503, body: '{}' }))
  await page.goto('/account')
  await expect(page.getByRole('button', { name: '重新连接会话' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeDisabled()
  await page.unroute('**/api/session')
  await page.getByRole('button', { name: '重新连接会话' }).click()
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  await page.getByRole('link', { name: '← 返回创作', exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
})
