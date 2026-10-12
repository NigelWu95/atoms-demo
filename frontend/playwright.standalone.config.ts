import { defineConfig, devices } from '@playwright/test'
import { readEvidence } from './e2e-standalone/fixtures'

const { baseURL } = readEvidence()
const noProxy = [process.env.NO_PROXY, process.env.no_proxy, '127.0.0.1,localhost,::1']
  .filter(Boolean)
  .join(',')
process.env.NO_PROXY = noProxy
process.env.no_proxy = noProxy

export default defineConfig({
  testDir: './e2e-standalone',
  outputDir: './test-results/standalone',
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: { baseURL, trace: 'off', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  // https://playwright.dev/docs/test-webserver
  webServer: {
    command: 'node ../scripts/serve-standalone.mjs',
    url: `${baseURL}/api/health`,
    reuseExistingServer: false,
    timeout: 120000,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 },
  },
})
