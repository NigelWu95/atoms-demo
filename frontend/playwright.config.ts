import { defineConfig } from '@playwright/test'
import runtime from './playwright.runtime.config'

export default defineConfig({
  ...runtime,
  testDir: './e2e',
  outputDir: './test-results',
  use: {
    ...runtime.use,
    baseURL: 'http://127.0.0.1:5174',
  },
})
