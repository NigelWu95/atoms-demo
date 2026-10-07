import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

const securityHeaders = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
}
const contentPolicy =
  "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

// Same-origin API proxy keeps database credentials and CORS out of the browser.
// Source: https://vite.dev/config/server-options.html#server-proxy
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8080' },
    headers: {
      ...securityHeaders,
      // Vite HMR injects style tags and uses a local WebSocket in development only.
      'Content-Security-Policy': contentPolicy
        .replace("style-src 'self'", "style-src 'self' 'unsafe-inline'")
        .replace("connect-src 'self'", "connect-src 'self' ws://127.0.0.1:5173"),
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8080' },
    headers: { ...securityHeaders, 'Content-Security-Policy': contentPolicy },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    clearMocks: true,
    restoreMocks: true,
  },
})
