import { defineConfig, devices } from '@playwright/test'

// Started by scripts/smoke-release.mjs. Only local test DB and loopback TLS are used.
export default defineConfig({
  testDir: './e2e-release',
  outputDir: './test-results-release',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'https://127.0.0.1:18443',
    ignoreHTTPSErrors: true, // Test-only self-signed certificate; never deployment config.
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  webServer: {
    command:
      './mvnw -B spring-boot:test-run -Dspring-boot.run.main-class=dev.atoms.fixtures.FixtureApplication',
    cwd: '../backend',
    url: 'http://127.0.0.1:8081/test-lab',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 },
  },
})
