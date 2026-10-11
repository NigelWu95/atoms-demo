import { defineConfig } from '@playwright/test'
import documentConfig from './playwright.standalone-document.config'

// Playwright's readiness probe honors proxy environment variables. Local test
// traffic must go directly to this run's listeners, never an inherited proxy.
const noProxy = [process.env.NO_PROXY, process.env.no_proxy, '127.0.0.1,localhost,::1']
  .filter(Boolean)
  .join(',')
process.env.NO_PROXY = noProxy
process.env.no_proxy = noProxy

const backend = documentConfig.webServer
const backendURL = documentConfig.use?.baseURL
if (!backend || Array.isArray(backend) || !backendURL)
  throw new Error('A dedicated standalone backend is required.')
const frontend = {
  cwd: '../templates/standalone-v1/frontend',
  env: { STANDALONE_DEV_API_PORT: new URL(backendURL).port },
  reuseExistingServer: false,
  timeout: 30000,
  gracefulShutdown: { signal: 'SIGTERM' as const, timeout: 10000 },
}

export default defineConfig({
  ...documentConfig,
  testDir: './e2e-standalone-shell',
  outputDir: './test-results/standalone-shell',
  use: { ...documentConfig.use, baseURL: 'http://127.0.0.1:5273' },
  webServer: [
    backend,
    { ...frontend, command: 'npm run dev', url: 'http://127.0.0.1:5273' },
    { ...frontend, command: 'npm run preview', url: 'http://127.0.0.1:4273' },
  ],
})
