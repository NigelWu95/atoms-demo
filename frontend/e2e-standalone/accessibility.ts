import AxeBuilder from '@axe-core/playwright'
import type { Locator, Page, TestInfo } from '@playwright/test'
import { expect } from './fixtures'

// Real keyboard traversal, including entering/leaving the opaque application frame.
export async function tabTo(page: Page, target: Locator, key: 'Tab' | 'Shift+Tab' = 'Tab') {
  for (let count = 0; count < 16; count++) {
    if (
      await target.evaluate(
        (element) =>
          element.ownerDocument.hasFocus() && element.ownerDocument.activeElement === element,
      )
    )
      break
    await page.keyboard.press(key)
  }
  await expect(target).toBeFocused()
  await expect(target).toBeInViewport()
  expect(
    await target.evaluate((element) => {
      const style = getComputedStyle(element)
      return style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) >= 2
    }),
    'Keyboard focus must have a visible outline',
  ).toBe(true)
}

export async function auditError(
  page: Page,
  info: TestInfo,
  control: '#retry' | '#latest' | 'none',
) {
  // Default modern axe mode recursively audits child frames, including opaque origins.
  // https://playwright.dev/docs/accessibility-testing
  const audit = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()
  const summarize = (results: typeof audit.passes) =>
    results.map(({ id, nodes }) => ({
      id,
      targets: nodes.map(({ target }) => target),
    }))
  await info.attach('error-accessibility', {
    body: JSON.stringify({
      violations: summarize(audit.violations),
      incomplete: summarize(audit.incomplete),
      passes: summarize(audit.passes),
    }),
    contentType: 'application/json',
  })
  expect(audit.violations).toEqual([])
  // Axe cannot measure the standalone check glyph (nonBmp). Review this one exact
  // decoration, never exclude a node/rule or silently accept other incomplete checks.
  expect(summarize(audit.incomplete)).toEqual(
    control === 'none' ? [] : [{ id: 'color-contrast', targets: [['.status-marker']] }],
  )
  for (const rule of audit.incomplete) {
    for (const node of rule.nodes) {
      expect(node.any.map(({ id, data }) => ({ id, data }))).toEqual([
        { id: 'color-contrast', data: { messageKey: 'nonBmp' } },
      ])
      expect(node.all).toEqual([])
      expect(node.none).toEqual([])
    }
  }
  await expect(page.locator('iframe')).toHaveCount(control === 'none' ? 0 : 1)
  if (control !== 'none') {
    const marker = page.locator('.status-marker')
    await expect(marker).toHaveAttribute('aria-hidden', 'true')
    await expect(marker).toHaveText('✓')
    await expect(page.getByRole('status', { name: '服务连接状态' })).toContainText('应用信息已就绪')
    // Reviewed colors have 13.80:1 contrast. Pin them so a design change requires review.
    // https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html
    await expect(marker).toHaveCSS('color', 'rgb(217, 244, 126)')
    await expect(marker).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    await expect(marker).toHaveCSS('opacity', '1')
    await expect(page.locator('.connection-panel')).toHaveCSS('background-color', 'rgb(26, 30, 27)')
    // Prevent a false green if a scanner silently skips the sandbox document.
    for (const [rule, selector] of [
      ['label', '#title'],
      ['button-name', control],
    ]) {
      expect(
        audit.passes
          .find(({ id }) => id === rule)
          ?.nodes.some(({ target }) => target.length > 1 && target.at(-1) === selector),
        `Axe must audit ${selector} inside the frame`,
      ).toBe(true)
    }
  }
  for (const frame of page.frames()) {
    expect(await frame.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    )
  }
  await page.bringToFront()
}
