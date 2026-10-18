import type { Page } from '@playwright/test'

export async function openTool(page: Page, label: string) {
  const summary = page.locator('.tool-disclosure > summary').filter({ hasText: label })
  if ((await summary.locator('..').getAttribute('open')) === null) await summary.click()
}
