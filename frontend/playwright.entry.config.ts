import { defineConfig } from '@playwright/test'
import runtime from './playwright.runtime.config'

// Welcome, account and project tests use the dedicated fixture database and
// captured email adapter. Never run these against the daily SMTP/model setup.
export default defineConfig({
  ...runtime,
  testDir: './e2e',
  outputDir: './test-results/entry',
  use: { ...runtime.use, baseURL: 'http://127.0.0.1:5174' },
})
