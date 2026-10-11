import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'
import { apiProxy, backendTarget, loopbackBoundary, pageHeaders } from './dev/boundary.ts'

// This port-only setting is consumed by Node, never exposed to the browser.
// https://vite.dev/config/server-options#server-proxy
const target = backendTarget(process.env.STANDALONE_DEV_API_PORT)
export default defineConfig({
  plugins: [loopbackBoundary(), vue()],
  envDir: false,
  envPrefix: [],
  server: {
    host: '127.0.0.1',
    port: 5273,
    strictPort: true,
    cors: false,
    forwardConsole: false,
    fs: {
      strict: true,
      allow: [fileURLToPath(new URL('.', import.meta.url))],
      deny: ['.env', '.env.*', '*.pem', '*.key', '*.properties', '**/.git/**'],
    },
    headers: pageHeaders(5273, true),
    proxy: apiProxy(target, 5273),
  },
  preview: {
    host: '127.0.0.1',
    port: 4273,
    strictPort: true,
    cors: false,
    headers: pageHeaders(4273, false),
    proxy: apiProxy(target, 4273),
  },
  build: { target: 'es2022', sourcemap: false },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts', 'dev/**/*.spec.ts'],
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
    passWithNoTests: false,
  },
})
