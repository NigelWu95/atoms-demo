import { expect, test } from '@playwright/test'

// A browser-only protocol fixture, not the future Vue shell or a persistent state service.
// Application documents, session, metadata and security headers all come from the real backend.
const host = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<link rel="icon" href="data:,"><title>Document protocol verification</title></head><body>
<iframe title="受限应用" sandbox="allow-scripts"></iframe><output id="status">connecting</output>
<script src="/__document-check.js"></script></body></html>`
const bridge = `
const frame = document.querySelector('iframe');
const channelId = '5a644d0b-5683-463d-bfe2-60d35f716a33';
const protocol = 'atoms-runtime-v1';
let state = {value:{count:0},revision:0,stateSchemaVersion:1};
window.addEventListener('message', event => {
  const data = event.data;
  if (event.source !== frame.contentWindow || event.origin !== 'null' ||
      data?.protocol !== protocol || data.channelId !== channelId || data.type !== 'HELLO') return;
  const channel = new MessageChannel();
  channel.port1.onmessage = ({data: call}) => {
    if (call.type === 'RUNTIME_READY') document.querySelector('#status').textContent = 'ready';
    if (!['STATE_GET','STATE_SAVE'].includes(call.type)) return;
    if (call.type === 'STATE_SAVE') state = {value:call.payload.value,revision:state.revision+1,stateSchemaVersion:1};
    channel.port1.postMessage({protocol,channelId,type:'RESULT',requestId:call.requestId,ok:true,result:state});
  };
  frame.contentWindow.postMessage({protocol,channelId,type:'CONNECT'}, '*', [channel.port2]);
}, {once:true});
frame.onload = () => frame.contentWindow.postMessage({protocol,channelId,type:'INIT'}, '*');
frame.src = '/api/app/document#atoms-channel=' + channelId;
`

test('real document runs in an opaque iframe with the compatible SDK', async ({
  page,
  context,
}, info) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (entry) => {
    if (['error', 'warning'].includes(entry.type())) errors.push(entry.text())
  })
  // APIRequestContext keeps cookies in this isolated browser context; no tokens in DOM or logs.
  // https://playwright.dev/docs/api/class-apirequestcontext
  const csrf = await (await context.request.get('/api/csrf')).json()
  expect(
    (
      await context.request.post('/api/session', {
        data: {},
        headers: { [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(200)
  await page.route('**/__document-check', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: host,
      headers: {
        'Content-Security-Policy':
          "default-src 'none'; script-src 'self'; frame-src 'self'; img-src data:; base-uri 'none'",
      },
    }),
  )
  await page.route('**/__document-check.js', (route) =>
    route.fulfill({ contentType: 'text/javascript', body: bridge }),
  )
  const loaded = page.waitForResponse((response) => response.url().endsWith('/api/app/document'))
  await page.goto('/__document-check')
  const response = await loaded
  expect(response.status()).toBe(200)
  expect(response.headers()['x-frame-options']).toBe('SAMEORIGIN')
  expect(response.headers()['content-security-policy']).toContain('sandbox allow-scripts')
  await expect(page.locator('#status')).toHaveText('ready')
  const button = page.frameLocator('iframe').locator('#count')
  await button.click()
  await expect(button).toHaveText('1')
  await button.press('Space')
  await expect(button).toHaveText('2')
  const frame = page.frames().find((entry) => entry !== page.mainFrame())
  expect(await frame?.evaluate(() => window.origin)).toBe('null')
  expect((await context.request.get('/api/app')).headers()['x-frame-options']).toBe('DENY')
  await page.screenshot({ path: info.outputPath('standalone-document.png') })
  expect(errors).toEqual([])
})

test('direct document navigation stays opaque and a fresh visitor cannot read it', async ({
  page,
  context,
  browser,
}) => {
  const csrf = await (await context.request.get('/api/csrf')).json()
  expect(
    (
      await context.request.post('/api/session', {
        data: {},
        headers: { [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(200)
  expect((await page.goto('/api/app/document'))?.status()).toBe(200)
  expect(await page.evaluate(() => window.origin)).toBe('null')
  await expect(page.locator('#count')).toHaveText('0')
  const stranger = await browser.newContext()
  try {
    expect((await stranger.request.get(page.url())).status()).toBe(401)
  } finally {
    await stranger.close()
  }
})
