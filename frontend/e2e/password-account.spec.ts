import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test('account profile, password setup/login/change and persistent workspace work together', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const email = `${crypto.randomUUID()}@example.com`
  const password = 'test-only-long-passphrase',
    next = 'another-test-only-passphrase'
  await page.goto('/')
  await expect(page.locator('.sidebar')).toHaveCount(0)
  await page.getByRole('textbox', { name: '描述你的应用想法' }).fill('登录之后继续制作的小游戏')
  await page.getByRole('link', { name: '注册 / 登录', exact: true }).click()
  await page.getByLabel('邮箱地址', { exact: true }).fill(email)
  const sent = page.waitForResponse('**/api/auth/email/challenges'),
    sentAt = Date.now()
  await page.getByRole('button', { name: '发送验证码', exact: true }).click()
  const loginChallenge = await (await sent).json()
  const loginMail = await page.request.get(`/api/test/email-codes/${loginChallenge.challengeId}`)
  expect(loginMail.ok()).toBe(true)
  await page.getByLabel('6 位验证码').fill((await loginMail.json()).code)
  await page.getByRole('button', { name: '验证并登录', exact: true }).click()
  await expect(page).toHaveURL('http://127.0.0.1:5174/create')
  await expect(page.locator('.sidebar')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '描述你的应用想法' })).toHaveValue(
    '登录之后继续制作的小游戏',
  )
  await page.getByRole('link', { name: '管理账号', exact: true }).click()
  await expect(page).toHaveURL(/\/settings\/account$/)
  await expect(page.locator('.account-facts')).toContainText(email)
  await expect(page.locator('.account-facts time')).toBeVisible()
  await expect(page.getByRole('button', { name: '发送设置密码验证码', exact: true })).toBeEnabled()
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(
      (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze())
        .violations,
    ).toEqual([])
    await page.screenshot({
      path: info.outputPath(`account-settings-${width}.png`),
      fullPage: true,
    })
  }
  // Preserve the real email-wide cooldown; only the delivery adapter is captured.
  await page.waitForTimeout(Math.max(0, sentAt + 62000 - Date.now()))
  const setupReply = page.waitForResponse('**/api/account/password/challenges')
  await page.getByRole('button', { name: '发送设置密码验证码', exact: true }).click()
  const setup = await setupReply
  expect(setup.status()).toBe(201)
  const setupMail = await page.request.get(
    `/api/test/email-codes/${(await setup.json()).challengeId}`,
  )
  await page.getByLabel('6 位验证码').fill((await setupMail.json()).code)
  const checked = page.waitForResponse('**/api/me')
  await page.locator('body').dispatchEvent('visibilitychange')
  expect((await checked).status()).toBe(200)
  await expect(page.locator('#password-code')).not.toHaveValue('')
  await page.getByLabel('新密码', { exact: true }).fill(password)
  await page.getByLabel('确认新密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '设置密码', exact: true }).click()
  await expect(
    page.getByText('密码已更新。当前账号保持登录，其他浏览器会话需要重新登录。', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('heading', { name: '修改密码', exact: true })).toBeVisible()

  const separate = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  try {
    const login = await separate.newPage()
    await login.goto('/account?returnTo=/create')
    await login.getByRole('button', { name: '密码登录', exact: true }).click()
    await login.getByLabel('邮箱地址', { exact: true }).fill(email)
    await login.getByLabel('账号密码', { exact: true }).fill(password)
    await login.getByRole('button', { name: '登录', exact: true }).click()
    await expect(login).toHaveURL('http://127.0.0.1:5174/create')
    await expect(login.locator('.sidebar')).toBeVisible()
    await page.getByLabel('原密码', { exact: true }).fill(password)
    await page.getByLabel('新密码', { exact: true }).fill(next)
    await page.getByLabel('确认新密码', { exact: true }).fill(next)
    const changed = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/account/password') && response.request().method() === 'PUT',
    )
    await page.getByRole('button', { name: '修改密码', exact: true }).click()
    expect((await changed).status()).toBe(200)
    await expect(page.locator('#new-password')).toHaveValue('')
    expect((await login.request.get('/api/me')).status()).toBe(401)
    await login.goto('/account?returnTo=/create')
    await login.getByRole('button', { name: '密码登录', exact: true }).click()
    await login.getByLabel('邮箱地址', { exact: true }).fill(email)
    await login.getByLabel('账号密码', { exact: true }).fill(password)
    await login.getByRole('button', { name: '登录', exact: true }).click()
    await expect(login.getByRole('alert')).toContainText('邮箱或密码不正确')
    await login.getByLabel('账号密码', { exact: true }).fill(next)
    await login.getByRole('button', { name: '登录', exact: true }).click()
    await expect(login).toHaveURL('http://127.0.0.1:5174/create')
    await login.goto('/')
    await expect(login).toHaveURL('http://127.0.0.1:5174/create')
    await login.reload()
    await expect(login.locator('.sidebar')).toBeVisible()
    await login.getByRole('link', { name: '我的项目', exact: true }).click()
    await login.getByRole('link', { name: '开始创作', exact: false }).click()
    await expect(login).toHaveURL('http://127.0.0.1:5174/create')
    await expect(login.getByRole('textbox', { name: '描述你的应用想法' })).toBeVisible()
    await login.getByRole('link', { name: '管理账号', exact: true }).click()
    await login.getByRole('button', { name: '退出账号', exact: true }).click()
    await expect(login).toHaveURL('http://127.0.0.1:5174/create')
    await expect(login.locator('.sidebar')).toBeVisible()
    await expect(login.getByRole('link', { name: '邮箱注册 / 登录', exact: true })).toBeVisible()
  } finally {
    await separate.close()
  }
  expect(errors).toEqual([])
})
