import { test, expect, ready, add, saved, state } from './fixtures'

test('real process restart restores the same visitor and keeps a second visitor isolated', async ({
  page,
  browser,
  runtime,
  baseURL,
}, info) => {
  const visitor = await ready(page)
  await add(page, '重启后仍属于我')
  await saved(page)
  const before = await state(page)
  await runtime.restart()
  expect((await ready(page)) === visitor).toBe(true)
  expect(await state(page)).toEqual(before)
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText('重启后仍属于我')

  const other = await browser.newContext()
  const origins = new Set<string>()
  other.on('request', (request) => origins.add(new URL(request.url()).origin))
  await other.route(
    (url) => url.origin !== baseURL,
    (route) => route.abort('blockedbyclient'),
  )
  try {
    const second = await other.newPage()
    expect((await ready(second)) === visitor).toBe(false)
    expect((await state(second)).revision).toBe(0)
    await expect(second.frameLocator('iframe').getByRole('listitem')).toHaveCount(0)
    await add(second, '第二位访客自己的数据')
    await saved(second)
    expect((await state(second)).revision).toBe(1)
    expect(await state(page)).toEqual(before)
    expect([...origins]).toEqual([baseURL])
    await page.screenshot({ path: info.outputPath('restored-after-restart.png'), fullPage: true })
  } finally {
    await other.close()
  }
})
