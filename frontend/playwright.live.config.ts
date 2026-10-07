import { defineConfig, devices } from '@playwright/test'

// Paid acceptance is deliberately outside both default test directories and CI.
if (process.env.ATOMS_LIVE_ACCEPTANCE !== 'up-to-6-requests')
  throw new Error('Live model acceptance requires explicit ATOMS_LIVE_ACCEPTANCE=up-to-6-requests')

export default defineConfig({
  testDir: './e2e-live',
  outputDir: './test-results/live',
  workers: 1,
  retries: 0,
  timeout: 780000,
  expect: { timeout: 10000 },
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    viewport: { width: 1440, height: 1000 },
    screenshot: 'only-on-failure',
    trace: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
})
