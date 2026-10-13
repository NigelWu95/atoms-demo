import { defineConfig } from '@playwright/test'
import runtime from './playwright.runtime.config'

// Run the unchanged foundation/project/SSE suite against the current test
// backend, not the user's already-running 8080 service or paid model config.
export default defineConfig({
  ...runtime,
  testDir: './e2e',
  outputDir: './test-results',
  use: { ...runtime.use, baseURL: 'http://127.0.0.1:5174' },
})
