// Verify the controlled template UI only; the API is a local fixture, not Java/database startup.
import assert from 'node:assert/strict'
import http from 'node:http'
import { mkdir, mkdtemp } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../frontend/node_modules/@playwright/test/index.mjs'
import AxeBuilder from '../frontend/node_modules/@axe-core/playwright/dist/index.mjs'
import { createServer, preview } from '../templates/full-project-v1/frontend/node_modules/vite/dist/node/index.js'

const root = fileURLToPath(new URL('../templates/full-project-v1/frontend/', import.meta.url))
const artifacts = fileURLToPath(new URL('../.local/', import.meta.url))
await mkdir(artifacts, { recursive: true })
const output = await mkdtemp(artifacts + 'native-seed-browser-')
let responseMode = 'ready'
const calls = []
const api = http.createServer((request, response) => {
  calls.push({ path: request.url, cookie: Boolean(request.headers.cookie), auth: Boolean(request.headers.authorization) })
  response.setHeader('Content-Type', 'application/json')
  if (request.url !== '/api/health') { response.statusCode = 404; response.end('{}'); return }
  if (responseMode === 'error') { response.statusCode = 503; response.end('{"message":"PRIVATE_BROWSER_CANARY"}'); return }
  const send = () => response.end('{"status":"UP","stage":"INITIALIZED"}')
  if (responseMode === 'slow') setTimeout(send, 800)
  else send()
})
await new Promise((resolve, reject) => { api.once('error', reject); api.listen(0, '127.0.0.1', resolve) })
process.env.FULL_PROJECT_DEV_API_PORT = String(api.address().port)
let dev, built, browser
try {
  dev = await createServer({ root, configFile: root + 'vite.config.ts', logLevel: 'error' })
  await dev.listen()
  built = await preview({ root, configFile: root + 'vite.config.ts', logLevel: 'error' })
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext()
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url())
    return url.protocol === 'http:' && url.hostname === '127.0.0.1' && ['5275', '4275'].includes(url.port)
      ? route.continue() : route.abort()
  })
  const page = await context.newPage()
  const errors = [], exceptions = [], requests = []
  page.on('console', (entry) => { if (['error', 'warning'].includes(entry.type())) errors.push(entry.text()) })
  page.on('pageerror', (error) => exceptions.push(error.message))
  page.on('request', (request) => requests.push(request.url()))
  for (const port of [5275, 4275]) {
    await page.goto(`http://127.0.0.1:${port}/`)
    await page.getByRole('status').filter({ hasText: '前后端已连通' }).waitFor()
    assert.equal(await page.getByRole('heading', { level: 1 }).count(), 1)
    assert.match(await page.locator('main').innerText(), /尚未生成业务功能/)
    for (const width of [320, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
      const button = await page.getByRole('button').boundingBox()
      assert.ok(button.width >= 44 && button.height >= 44)
    }
    await page.reload()
    await page.getByRole('status').filter({ hasText: '前后端已连通' }).waitFor()
    await page.keyboard.press('Tab')
    assert.equal(await page.getByRole('link', { name: '跳到主要内容' }).evaluate((node) => node === document.activeElement), true)
    await page.keyboard.press('Enter')
    assert.equal(await page.locator('main').evaluate((node) => node === document.activeElement), true)
    await page.keyboard.press('Tab')
    assert.equal(await page.getByRole('button').evaluate((node) => node === document.activeElement), true)
    const before = calls.length
    await page.keyboard.press('Enter')
    await page.getByRole('status').filter({ hasText: '前后端已连通' }).waitFor()
    assert.ok(calls.length > before)
    assert.deepEqual((await new AxeBuilder({ page }).analyze()).violations, [])
    await page.screenshot({ path: `${output}/${port}-desktop.png`, fullPage: true })
    await page.setViewportSize({ width: 320, height: 900 })
    await page.screenshot({ path: `${output}/${port}-mobile.png`, fullPage: true })
  }
  assert.deepEqual(errors, [])
  responseMode = 'slow'
  await page.reload()
  await page.getByRole('status').filter({ hasText: '正在检查' }).waitFor()
  assert.equal(await page.getByRole('button').isDisabled(), true)
  await page.getByRole('status').filter({ hasText: '前后端已连通' }).waitFor()
  responseMode = 'error'
  await page.getByRole('button').click()
  await page.getByRole('alert').waitFor()
  assert.ok(!(await page.locator('body').innerText()).includes('PRIVATE_BROWSER_CANARY'))
  responseMode = 'ready'
  await page.getByRole('button', { name: '重试连接' }).click()
  await page.getByRole('status').filter({ hasText: '前后端已连通' }).waitFor()
  assert.deepEqual(exceptions, [])
  assert.ok(errors.length <= 1 && errors.every((message) => message.includes('503')))
  assert.ok(requests.every((url) => /^https?:\/\/127\.0\.0\.1:(5275|4275)\//.test(url)))
  assert.ok(calls.every((call) => call.path === '/api/health' && !call.cookie && !call.auth))
  const timing = await page.evaluate(() => ({ domContentLoadedMs: performance.getEntriesByType('navigation')[0]?.domContentLoadedEventEnd, elements: document.querySelectorAll('*').length }))
  console.log(JSON.stringify({ result: 'passed', widths: [320, 768, 1024, 1440], modes: ['dev', 'built-preview'], timing, api: 'local deterministic fixture, not a running Java/database application', screenshots: output }))
} finally {
  await browser?.close()
  await dev?.close()
  await new Promise((resolve) => { if (built) built.httpServer.close(resolve); else resolve() })
  api.closeAllConnections()
  await new Promise((resolve) => api.close(resolve))
}
