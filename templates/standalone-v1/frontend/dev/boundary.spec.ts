// @vitest-environment node
import type { IncomingMessage } from 'node:http'
import { describe, expect, it } from 'vitest'
import { backendTarget, isAllowedRequest, pageHeaders } from './boundary'

function request(host: string, origin?: string, site?: string) {
  return {
    headers: { host, origin, 'sec-fetch-site': site },
    rawHeaders: ['Host', host, ...(origin === undefined ? [] : ['Origin', origin])],
  } satisfies Pick<IncomingMessage, 'headers' | 'rawHeaders'>
}

describe('loopback development boundary', () => {
  it('only accepts a numeric, unprivileged backend port on fixed loopback', () => {
    expect(backendTarget(undefined)).toBe('http://127.0.0.1:8088')
    expect(backendTarget('18088')).toBe('http://127.0.0.1:18088')
    for (const input of ['', '80', '65536', '08088', '8088/path', 'https://evil.test', '8088\n'])
      expect(() => backendTarget(input)).toThrow('STANDALONE_DEV_API_PORT')
  })

  it('allows navigation and exact same-origin requests', () => {
    expect(isAllowedRequest(request('127.0.0.1:5273'), 5273)).toBe(true)
    expect(
      isAllowedRequest(request('127.0.0.1:5273', 'http://127.0.0.1:5273', 'same-origin'), 5273),
    ).toBe(true)
  })

  it.each(['null', 'https://evil.test', 'http://127.0.0.1:5274', 'http://127.0.0.1:5273/'])(
    'rejects Origin %s before any proxy rewrite',
    (origin) => {
      expect(isAllowedRequest(request('127.0.0.1:5273', origin), 5273)).toBe(false)
    },
  )

  it('rejects rebinding, cross-site and duplicated Origin headers', () => {
    expect(isAllowedRequest(request('evil.test:5273'), 5273)).toBe(false)
    expect(isAllowedRequest(request('127.0.0.1:5273', undefined, 'cross-site'), 5273)).toBe(false)
    const repeated = request('127.0.0.1:5273', 'http://127.0.0.1:5273')
    repeated.rawHeaders.push('origin', 'http://127.0.0.1:5273')
    expect(isAllowedRequest(repeated, 5273)).toBe(false)
  })

  it('keeps inline scripts forbidden and development-only allowances out of preview', () => {
    const dev = pageHeaders(5273, true)['Content-Security-Policy']
    const preview = pageHeaders(4273, false)['Content-Security-Policy']
    expect(dev).toContain("script-src 'self';")
    expect(dev).toContain("style-src 'self' 'unsafe-inline'")
    expect(dev).toContain('ws://127.0.0.1:5273')
    expect(preview).not.toMatch(/unsafe-inline|ws:/)
    expect(preview).toContain("frame-src 'self'")
    expect(pageHeaders(5273, true)['X-Frame-Options']).toBe('DENY')
  })
})
