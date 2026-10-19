import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'
import { apiProxy, backendTarget, loopbackBoundary, pageHeaders } from './dev/boundary.ts'

// Configuration is server-side only; no .env files or environment values enter the bundle.
// https://vite.dev/config/shared-options.html#envdir
const root = fileURLToPath(new URL('.', import.meta.url))
const target = backendTarget(process.env.FULL_PROJECT_DEV_API_PORT)
export default defineConfig({
  root,
  base: './',
  plugins: [loopbackBoundary(), vue()],
  envDir: false,
  envPrefix: [],
  publicDir: false,
  server: {
    host: '127.0.0.1',
    port: 5275,
    strictPort: true,
    cors: false,
    forwardConsole: false,
    fs: {
      strict: true,
      allow: [root],
      deny: [
        '.env',
        '.env.*',
        '*.{crt,pem,key,p12,pfx,cer,der}',
        '*.properties',
        '.npmrc',
        '.yarnrc.yml',
        '**/.git/**',
      ],
    },
    headers: pageHeaders(5275, true),
    proxy: apiProxy(target, 5275),
  },
  preview: {
    host: '127.0.0.1',
    port: 4275,
    strictPort: true,
    cors: false,
    headers: pageHeaders(4275, false),
    proxy: apiProxy(target, 4275),
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
