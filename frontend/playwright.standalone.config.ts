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
  // The opaque frame's CSP forbids workers; the reviewed host has no worker code.
  // Playwright's block init script reads navigator.serviceWorker
  // inside the opaque frame and throws SecurityError; do not inject that script or ignore errors.
  use: { baseURL, trace: 'off', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  // The worker fixture supports true process restarts while preserving no-reuse/occupied-port failure.
})
