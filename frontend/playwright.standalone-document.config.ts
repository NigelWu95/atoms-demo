import { readFileSync } from 'node:fs'
import { isAbsolute } from 'node:path'
import { defineConfig, devices } from '@playwright/test'

// Only discover the test listener here. Java independently validates the whole configuration
// and the actual dedicated database before starting. Never log configuration contents.
const config = process.env.STANDALONE_TEST_CONFIG
if (!config || !isAbsolute(config))
  throw new Error('Explicit standalone test configuration required.')
const contents = readFileSync(config, 'utf8')
const port = /^server\.port=(\d+)\s*$/m.exec(contents)?.[1]
if (!port || Number(port) < 1024 || Number(port) > 65535)
  throw new Error('Dedicated test listener is invalid.')
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e-standalone-document',
  outputDir: './test-results/standalone-document',
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: { baseURL, trace: 'off', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  // https://playwright.dev/docs/test-webserver
  webServer: {
    command: './mvnw -B spring-boot:test-run',
    cwd: '../templates/standalone-v1/backend',
    url: `${baseURL}/api/health`,
    reuseExistingServer: false,
    timeout: 120000,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 },
  },
})
