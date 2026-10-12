import AxeBuilder from '@axe-core/playwright'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { verifiedProject } from '../../scripts/serve-standalone.mjs'
import { test, expect } from './fixtures'

for (const width of [320, 390, 768, 1440]) {
  test(`assembled JAR serves the real application at ${width}px`, async ({
    page,
    baseURL,
    loadedRuntimeId,
  }, info) => {
    // Both assembled and UI-downloaded fixtures must render their own source title.
    // Derive the expectation from the verified source, not from the API or current DOM.
    const project = await verifiedProject(process.env.STANDALONE_VERIFICATION_REPORT)
    const manifest: { title: string } = JSON.parse(
      await readFile(join(project.directory, 'application/manifest.json'), 'utf8'),
    )
    expect(manifest.title).toEqual(expect.any(String))
    expect(manifest.title.length).toBeGreaterThan(0)
    const failures: string[] = []
    const origins = new Set<string>()
    page.on('pageerror', (error) => failures.push(error.name))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) failures.push(message.type())
    })
    page.on('request', (request) => origins.add(new URL(request.url()).origin))
    await page.setViewportSize({ width, height: 900 })
    const appResponse = page.waitForResponse('**/api/app').then((response) => response.json())
    const stateResponse = page.waitForResponse('**/api/state').then((response) => response.status())
    await page.goto('/')
    expect(await appResponse).toMatchObject({ loadedRuntimeId, title: manifest.title })
    expect(await stateResponse).toBe(200)
    await expect(
      page.getByRole('heading', { name: manifest.title, level: 1, exact: true }),
    ).toBeVisible()
    const frame = page.frameLocator('iframe')
    await expect(frame.getByRole('button', { name: '添加待办' })).toBeEnabled()
    await expect(frame.locator('#status')).toHaveText('数据已读取。')
    await expect(page.locator('iframe')).toHaveAttribute('sandbox', 'allow-scripts')
    expect(
      await page
        .locator('iframe')
        .evaluate((element) => (element as HTMLIFrameElement).contentDocument === null),
    ).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
    const audit = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze()
    expect(audit.violations).toEqual([])
    expect([...origins]).toEqual([baseURL])
    expect(failures).toEqual([])
    if (width === 320 || width === 1440)
      await page.screenshot({ path: info.outputPath(`assembled-${width}.png`), fullPage: true })
  })
}
