import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e-runtime',
  outputDir: './test-results-runtime',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:8081',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  webServer: [
    {
      // The test-only entry point never ships with the application JAR.
      // https://playwright.dev/docs/test-webserver
      command: './mvnw -B spring-boot:test-run',
      cwd: '../backend',
      url: 'http://127.0.0.1:8081/test-lab',
      reuseExistingServer: !process.env.CI,
      timeout: 120000,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 },
    },
    {
      command: 'npm run dev:fixtures',
      url: 'http://127.0.0.1:5174',
      reuseExistingServer: !process.env.CI,
      timeout: 120000,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 },
    },
  ],
})
