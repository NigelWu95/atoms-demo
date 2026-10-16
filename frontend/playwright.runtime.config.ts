import { defineConfig, devices } from '@playwright/test'

// Readiness checks must reach this run's loopback servers, not a system HTTP proxy.
const noProxy = [process.env.NO_PROXY, process.env.no_proxy, '127.0.0.1,localhost,::1']
  .filter(Boolean)
  .join(',')
process.env.NO_PROXY = noProxy
process.env.no_proxy = noProxy

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
      command:
        './mvnw -B spring-boot:test-run -Dspring-boot.run.main-class=dev.atoms.fixtures.FixtureApplication -Dspring-boot.run.arguments=--atoms.generation.worker-enabled=false',
      // Admission tests need a configured gateway, never real credentials or execution.
      env: { DEEPSEEK_API_KEY: 'test-only-key', GENERATION_MAX_MODEL_CALLS: '100000' },
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
