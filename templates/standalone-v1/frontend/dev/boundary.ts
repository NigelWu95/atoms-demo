import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin, ProxyOptions } from 'vite'

export function backendTarget(port = '8088'): string {
  if (!/^[1-9]\d{3,4}$/.test(port) || Number(port) > 65535 || Number(port) < 1024)
    throw new Error('STANDALONE_DEV_API_PORT must be a port between 1024 and 65535.')
  return `http://127.0.0.1:${port}`
}

export function isAllowedRequest(
  request: Pick<IncomingMessage, 'headers' | 'rawHeaders'>,
  port: number,
): boolean {
  const host = `127.0.0.1:${port}`
  const origins = request.rawHeaders.filter(
    (_, i) => i % 2 === 0 && request.rawHeaders[i]?.toLowerCase() === 'origin',
  )
  return (
    request.headers.host === host &&
    origins.length <= 1 &&
    (request.headers.origin === undefined || request.headers.origin === `http://${host}`) &&
    request.headers['sec-fetch-site'] !== 'cross-site'
  )
}

export function pageHeaders(port: number, development: boolean): Record<string, string> {
  // Only HMR styles/WebSocket differ from the built preview. No inline scripts or remote assets.
  return {
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    'Content-Security-Policy':
      "default-src 'none'; script-src 'self'; " +
      `style-src 'self'${development ? " 'unsafe-inline'" : ''}; ` +
      `connect-src 'self'${development ? ` ws://127.0.0.1:${port}` : ''}; ` +
      "img-src 'self'; font-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  }
}

export function loopbackBoundary(): Plugin {
  const boundary =
    (port: number, development: boolean) =>
    (request: IncomingMessage, response: ServerResponse, next: () => void) => {
      // configureServer runs before Vite's proxy. Do not normalize an untrusted Origin.
      for (const [name, value] of Object.entries(pageHeaders(port, development)))
        response.setHeader(name, value)
      if (isAllowedRequest(request, port)) return next()
      response.statusCode = 403
      response.setHeader('Content-Type', 'application/json')
      response.end('{"code":"FORBIDDEN","retryable":false}')
    }
  return {
    name: 'standalone-loopback-boundary',
    configureServer(server) {
      server.middlewares.use(boundary(5273, true))
    },
    configurePreviewServer(server) {
      server.middlewares.use(boundary(4273, false))
    },
  }
}

export function apiProxy(target: string, port: number): Record<string, ProxyOptions> {
  return {
    '^/api(?:/|$)': {
      target,
      changeOrigin: true,
      configure(proxy) {
        proxy.on('proxyReq', (upstream, request) => {
          // The boundary has already checked Host, Origin and Fetch Metadata.
          // Translate ONLY that exact development origin; retain absent Origin.
          if (
            isAllowedRequest(request, port) &&
            request.headers.origin === `http://127.0.0.1:${port}`
          )
            upstream.setHeader('Origin', target)
        })
      },
    },
  }
}
