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
async function verify(page: Page, code: string) {
  await page.getByRole('textbox', { name: '6 位验证码' }).fill(code)
  await page.getByRole('button', { name: '验证并登录', exact: true }).click()
  await expect(page.getByText('邮箱账号 · 已登录', { exact: true })).toBeVisible()
}

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
  await page.getByRole('link', { name: '邮箱注册 / 登录' }).click()
  const email = `${crypto.randomUUID()}@example.com`,
    sentAt = Date.now()
  const code = await requestCode(page, email)
  await expect(page.getByRole('textbox', { name: '6 位验证码' })).toBeFocused()
  await expect(page.getByRole('button', { name: /秒后可重发/ })).toBeDisabled()
  await verify(page, code)
  expect((await (await page.request.get('/api/me')).json()).userId).toBe(owner)
  expect((await page.request.get(projectPath)).status()).toBe(200)
  await expect(sibling.getByRole('link', { name: '管理账号' })).toBeVisible()
  await expect(sibling.getByRole('textbox', { name: '描述你的应用想法' })).toHaveValue('')
  await page.screenshot({ path: info.outputPath('account-signed-in.png'), fullPage: true })
  await page.getByRole('button', { name: '退出账号', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  await expect(sibling.getByRole('link', { name: '邮箱注册 / 登录' })).toBeVisible()
  await expect(sibling.getByRole('link', { name: /邮箱注册前的项目/ })).toHaveCount(0)
  expect((await page.request.get(projectPath)).status()).toBe(404)

  const separate = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  try {
    const login = await separate.newPage()
    await login.goto('/account')
    await expect(login.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
    expect((await login.request.get(projectPath)).status()).toBe(404)
    // Exercise the real, fixed 60-second cooldown without any test-only bypass in production code.
    await login.waitForTimeout(Math.max(0, sentAt + 62000 - Date.now()))
    await verify(login, await requestCode(login, email))
    expect((await (await login.request.get('/api/me')).json()).userId).toBe(owner)
    expect((await login.request.get(projectPath)).status()).toBe(200)
    await login.reload()
    await expect(login.getByText('邮箱账号 · 已登录', { exact: true })).toBeVisible()
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
  await expect(page.getByRole('textbox', { name: '邮箱地址', exact: true })).toBeEnabled()
  for (const width of [320, 390, 1440]) {
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
  await expect(page.getByText('邮箱账号 · 已登录', { exact: true })).toBeVisible()
})
