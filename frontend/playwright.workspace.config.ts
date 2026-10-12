import { mkdtempSync, realpathSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, devices } from '@playwright/test'

const config = realpathSync('../.local/standalone')
process.env.WORKSPACE_BROWSER_OUTPUT ??= mkdtempSync(join(config, 'workspace-browser-'))
process.env.WORKSPACE_BROWSER_CONFIG = config

export default defineConfig({
  testDir: './e2e-workspace',
  outputDir: './test-results-workspace',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 720000,
  expect: { timeout: 15000 },
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  webServer: [
    {
      command: 'node scripts/serve-workspace-fixture.mjs',
      cwd: '..',
      url: 'http://127.0.0.1:8081/test-lab',
      reuseExistingServer: false,
      timeout: 120000,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 35000 },
    },
    {
      command: 'npm run dev:fixtures',
      url: 'http://127.0.0.1:5174',
      reuseExistingServer: false,
      timeout: 120000,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 },
    },
  ],
})
