import { readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createServer, request as upstreamRequest } from 'node:http'
import { once } from 'node:events'
import assert from 'node:assert/strict'

const config = JSON.parse(await readFile(process.argv[2], 'utf8'))
if (config.direct) assert.equal(config.iframePath, '/')
else assert.match(config.iframePath, /^\/api\/delivery\/preview\/[A-Za-z0-9_-]{43}\/$/)
const { chromium, expect } = createRequire(`${config.frontend}/package.json`)('@playwright/test')
const origin = config.direct ? 'http://127.0.0.1:18182' : 'http://127.0.0.1:5174'
const redact = text => config.direct ? text : text.replaceAll(config.iframePath, '/preview/[redacted]/')
let stage = 'listen', browser, blockedManagement = 0, externalRequests = 0
const proxyRequests = []
const server = createServer((request, response) => {
  if (request.url === '/') {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
      'Content-Security-Policy': `default-src 'none'; style-src 'unsafe-inline'; frame-src ${origin}${config.iframePath}; base-uri 'none'` })
    response.end(`<!doctype html><html><head><title>Delivery acceptance</title><style>html,body,iframe{width:100%;height:100%;margin:0;border:0}</style></head><body><iframe title="Actual application" sandbox="allow-scripts allow-forms" referrerpolicy="no-referrer" src="${config.iframePath}"></iframe></body></html>`)
  } else if (request.url.startsWith(config.iframePath)) {
    const started = performance.now()
    const observed = { method: request.method, path: request.url.slice(config.iframePath.length), contentType: request.headers['content-type'] }
    proxyRequests.push(observed)
    const upstream = upstreamRequest({ host: '127.0.0.1', port: 8081, path: request.url, method: request.method, headers: request.headers }, incoming => {
      observed.status = incoming.statusCode
      observed.elapsedMs = Math.round(performance.now() - started)
      response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response)
    })
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end() })
    request.pipe(upstream)
    response.on('close', () => { observed.closedMs = Math.round(performance.now() - started); upstream.destroy() })
  } else {
    if (request.url.startsWith('/api/')) blockedManagement++
    response.writeHead(404); response.end()
  }
})
const errors = [], responses = [], consoleErrors = [], failedRequests = []
async function app(page) {
  page.on('pageerror', error => errors.push(error.name))
  page.on('console', message => {
    if (message.type() === 'error' && consoleErrors.length < 20) consoleErrors.push(redact(message.text()))
  })
  page.on('request', request => { if (!request.url().startsWith(origin)) externalRequests++ })
  page.on('requestfailed', request => failedRequests.push({ method: request.method(), path: redact(request.url()), reason: request.failure()?.errorText }))
  page.on('response', response => {
    if (response.url().startsWith(origin + config.iframePath)) responses.push({ status: response.status(), type: response.headers()['content-type'] ?? '', formsAllowed: response.headers()['content-security-policy']?.includes('allow-scripts allow-forms') })
  })
  await page.goto(origin, { waitUntil: 'domcontentloaded' })
  const frame = config.direct ? page : page.frameLocator('iframe')
  await expect(frame.locator('main[data-sample-version="2"]')).toBeVisible({ timeout: 40000 })
  await expect(frame.getByRole('status')).toHaveText('列表已更新。', { timeout: 40000 })
  return frame
}
async function findRow(frame, id) {
  const row = frame.locator(`[data-todo-id="${id}"]`)
  for (let i = 0; i < 100 && !await row.count(); i++) {
    await frame.locator('[data-test="more"]').click()
    await expect(frame.locator('.tasks')).toHaveAttribute('aria-busy', 'false')
  }
  await expect(row).toBeVisible()
  return row
}
try {
  if (!config.direct) { server.listen(5174, '127.0.0.1'); await once(server, 'listening') }
  stage = 'launch'; browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  await context.addCookies([{ name: 'delivery_test_canary', value: 'synthetic-only', url: origin, httpOnly: true }])
  const page = await context.newPage(); stage = 'load'; const frame = await app(page)
  stage = 'create-input'; await frame.getByLabel('添加待办', { exact: true }).fill(config.marker)
  await frame.locator('#new-priority').selectOption('1')
  const createdResponse = page.waitForResponse(response => response.url().endsWith('/api/todos') && response.request().method() === 'POST')
  stage = 'create-submit'; await frame.locator('[data-test="add"]').click()
  const created = await createdResponse; assert.equal(created.status(), 201); const item = await created.json()
  assert.equal(item.priority, 1)
  const row = await findRow(frame, item.id)
  const other = await context.newPage(); stage = 'second-page'; const otherFrame = await app(other)
  const stale = await findRow(otherFrame, item.id)
  stage = 'edit'; await row.locator('[data-test="edit"]').click()
  await frame.getByLabel('修改待办', { exact: true }).fill(`${config.marker}-edited`)
  await frame.locator('#edit-priority').selectOption('2'); await frame.locator('[data-test="save"]').click()
  await expect(row.locator('.todo-title')).toHaveText(`${config.marker}-edited`)
  await expect(row.locator('.priority-badge')).toHaveText('紧急')
  stage = 'conflict'; await stale.locator('[data-test="toggle"]').click()
  await expect(otherFrame.getByRole('alert')).toContainText('已在别处修改')
  await otherFrame.locator('[data-test="refresh"]').click()
  await expect(otherFrame.locator('.tasks')).toHaveAttribute('aria-busy', 'false')
  await expect((await findRow(otherFrame, item.id)).locator('.todo-title')).toHaveText(`${config.marker}-edited`)
  await other.close()
  stage = 'sandbox'
  const actual = page.frames().find(value => value.url().startsWith(origin + config.iframePath))
  assert.ok(actual)
  const isolation = config.direct ? { standalone: true } : await actual.evaluate(async () => {
    const denied = action => { try { action(); return false } catch (error) { return error.name === 'SecurityError' } }
    const cookie = denied(() => document.cookie), storage = denied(() => localStorage.getItem('delivery_test_canary'))
    const external = await fetch('https://delivery-test.invalid/probe').then(() => false, () => true)
    const management = await fetch('/api/me').then(() => false, () => true)
    const topNavigation = denied(() => { window.top.location.href = '/outside' })
    return { origin: self.origin, cookie, storage, external, management, topNavigation }
  })
  if (!config.direct) assert.deepEqual(isolation, { origin: 'null', cookie: true, storage: true, external: true, management: true, topNavigation: true })
  assert.equal(page.url(), `${origin}/`); assert.equal(blockedManagement, 0); assert.equal(externalRequests, 0)
  assert.ok(responses.some(response => response.status === 409))
  assert.ok(responses.some(response => response.status === 200 && /javascript/.test(response.type)))
  assert.ok(responses.some(response => response.status === 200 && /text\/css/.test(response.type)))
  assert.deepEqual(errors, [])
  stage = 'screenshots'; await actual.evaluate(() => scrollTo(0, 0)); await page.screenshot({ path: `${config.directory}/desktop.png` })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(frame.getByRole('heading', { name: '今天，先做这几件。' })).toBeVisible()
  await actual.evaluate(() => scrollTo(0, 0))
  assert.ok(await actual.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await page.screenshot({ path: `${config.directory}/mobile.png` })
  const result = { passed: true, id: item.id, sampleVersion: 2, moduleLoaded: true, conflict: true, ...isolation }
  await writeFile(`${config.directory}/result.json`, JSON.stringify({ ...result, requests: proxyRequests }, null, 2), { mode: 0o600 })
  process.stdout.write(JSON.stringify(result))
} catch (error) {
  const page = browser?.contexts()[0]?.pages()[0]
  const frame = page?.frames().find(value => value.url().startsWith(origin + config.iframePath))
  const state = await frame?.evaluate(() => ({ text: document.querySelector('main')?.innerText,
    input: document.querySelector('#new-title')?.value, addDisabled: document.querySelector('[data-test="add"]')?.disabled })).catch(() => null)
  await page?.screenshot({ path: `${config.directory}/failure.png` }).catch(() => {})
  await writeFile(`${config.directory}/failure.json`, JSON.stringify({ stage, error: error.name, errors, consoleErrors,
    message: redact(error.message), responses, proxyRequests, failedRequests, state }), { mode: 0o600 })
  process.stderr.write(JSON.stringify({ failed: true, stage, error: error.name }))
  process.exitCode = 1
} finally {
  await browser?.close()
  if (server.listening) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) }
}
