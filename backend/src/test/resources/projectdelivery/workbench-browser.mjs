import { readFile, writeFile, stat } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createServer, request as proxyRequest } from 'node:http'
import { once } from 'node:events'
import { resolve, sep, extname } from 'node:path'
import { execFileSync } from 'node:child_process'
import assert from 'node:assert/strict'

const config = JSON.parse(await readFile(process.argv[2], 'utf8'))
const { chromium, expect } = createRequire(`${config.frontend}/package.json`)('@playwright/test')
const root = resolve(config.frontend, 'dist'), origin = 'http://127.0.0.1:5174'
await stat(resolve(root, 'index.html'))
const server = createServer(async (request, response) => {
  if (request.url.startsWith('/api/')) {
    const upstream = proxyRequest({ host: '127.0.0.1', port: 8081, path: request.url, method: request.method, headers: request.headers }, incoming => {
      response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response)
    })
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end() })
    response.on('close', () => upstream.destroy()); request.pipe(upstream); return
  }
  try {
    const path = new URL(request.url, origin).pathname
    const file = path.startsWith('/assets/') ? resolve(root, `.${decodeURIComponent(path)}`) : resolve(root, 'index.html')
    if (!file.startsWith(root + sep)) throw new Error('Rejected asset')
    const bytes = await readFile(file)
    response.writeHead(200, { 'Content-Type': ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' })[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store', 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'" })
    response.end(bytes)
  } catch { response.writeHead(404); response.end() }
})
let browser, page, stage = 'listen'
const applications = [], errors = []
try {
  server.listen(5174, '127.0.0.1'); await once(server, 'listening')
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await context.addCookies([{ name: 'ATOMS_SESSION', value: config.session, url: origin, httpOnly: true, sameSite: 'Lax' }])
  page = await context.newPage(); page.on('dialog', dialog => dialog.accept()); page.on('pageerror', error => errors.push(error.name))
  for (const [index, app] of config.applications.entries()) {
    stage = `open-${index}`; await page.goto(`${origin}/projects/${app.project}/engineering`)
    await expect(page.getByRole('heading', { name: '需求与任务', exact: true })).toBeVisible({ timeout: 60000 })
    await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeEnabled({ timeout: 60000 })
    stage = `inspect-${index}`; await page.getByRole('button', { name: '构建与运行 ▾', exact: true }).click()
    const builds = page.getByLabel('构建记录', { exact: true })
    for (let i = 0; i < 10 && !await builds.locator(`option[value="${app.build}"]`).count(); i++) {
      const count = await builds.locator('option').count()
      await page.getByRole('button', { name: '加载更早构建', exact: true }).click()
      await expect.poll(() => builds.locator('option').count()).toBeGreaterThan(count)
    }
    await builds.selectOption(app.build)
    await page.getByRole('button', { name: '核对数据库迁移', exact: true }).click()
    await expect(page.getByText('数据库历史与该构建一致。', { exact: true })).toBeVisible({ timeout: 45000 })
    stage = `start-${index}`; await page.getByRole('button', { name: '启动应用', exact: true }).click()
    await expect(page.getByRole('button', { name: '打开运行预览', exact: true })).toBeEnabled({ timeout: 180000 })
    await page.getByRole('button', { name: '构建与运行 ▴', exact: true }).click()
    stage = `preview-${index}`; await page.getByRole('button', { name: '打开运行预览', exact: true }).click()
    const frame = page.frameLocator('iframe[title="完整工程运行预览"]')
    await expect(frame.locator('main[data-sample-version="2"]')).toBeVisible({ timeout: 60000 })
    await expect(frame.getByRole('status')).toHaveText('列表已更新。', { timeout: 60000 })
    await frame.locator('[data-test="filter-open"]').click(); await expect(frame.locator('[data-test="filter-open"]')).toHaveAttribute('aria-pressed', 'true')
    await frame.getByLabel('添加待办', { exact: true }).fill('仅验证输入保持，不提交业务数据')
    stage = `stable-preview-${index}`; await page.getByRole('button', { name: '刷新', exact: true }).click()
    await page.getByRole('group', { name: '工程内容' }).getByRole('button', { name: '源码', exact: true }).click()
    await expect(page.locator('.engineering-file-tree button').first()).toBeVisible({ timeout: 45000 })
    await page.getByRole('group', { name: '工程内容' }).getByRole('button', { name: '预览', exact: true }).click()
    await expect(frame.getByLabel('添加待办', { exact: true })).toHaveValue('仅验证输入保持，不提交业务数据')
    stage = `download-${index}`; const download = page.waitForEvent('download', { timeout: 45000 })
    await page.getByRole('button', { name: '下载当前预览源码', exact: true }).click()
    const zip = `${config.directory}/preview-${index}.zip`; await (await download).saveAs(zip)
    const manifest = JSON.parse(execFileSync('/usr/bin/unzip', ['-p', zip, 'delivery/manifest.json'], { maxBuffer: 2 << 20 }).toString('utf8'))
    assert.equal(manifest.build.projectId, app.project); assert.equal(manifest.build.buildId, app.build); assert.equal(manifest.build.sourceVersionId, app.source)
    await page.screenshot({ path: `${config.directory}/application-${index}.png` })
    stage = `stop-${index}`; await page.getByRole('button', { name: '构建与运行 ▾', exact: true }).click()
    await page.getByRole('button', { name: '请求停止', exact: true }).first().click()
    await expect(page.locator('iframe[title="完整工程运行预览"]')).toHaveCount(0, { timeout: 60000 })
    await expect(page.getByRole('button', { name: '请求停止', exact: true })).toHaveCount(0, { timeout: 60000 })
    applications.push({ project: app.project, build: manifest.build.buildId, source: manifest.build.sourceVersionId, tree: manifest.treeSha256, stopped: true })
  }
  assert.deepEqual(errors, [])
  const result = { ok: true, applications, errors }
  await writeFile(`${config.directory}/result.json`, JSON.stringify(result, null, 2), { mode: 0o600 })
  process.stdout.write(JSON.stringify(result))
} catch (error) {
  if (page) await page.screenshot({ path: `${config.directory}/failure.png` }).catch(() => {})
  const reason = String(error.message).replace(/\/api\/delivery\/preview\/[A-Za-z0-9_-]+\//g, '/preview/[redacted]/')
  process.stdout.write(JSON.stringify({ ok: false, stage, reason, errors }))
  process.exitCode = 1
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
}
