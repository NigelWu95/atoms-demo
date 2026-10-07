import { afterEach, describe, expect, it, vi } from 'vitest'
import { checkHealth } from './health'

afterEach(() => vi.unstubAllGlobals())

describe('health API boundary', () => {
  it('accepts a real successful health payload', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    await expect(checkHealth()).resolves.toBeUndefined()
  })

  it.each([503, 404])('rejects HTTP %s instead of showing connected', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status })))
    await expect(checkHealth()).rejects.toThrow('服务暂不可用')
  })

  it.each(['{}', 'null', '{"status":"DOWN"}', '<html>fallback</html>'])(
    'rejects invalid health data: %s',
    async (body) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
      await expect(checkHealth()).rejects.toThrow()
    },
  )
})
