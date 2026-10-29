import { expect, it } from 'vitest'
import { decodeLocalAddress } from './projectDelivery'

const address = {
  runId: '00000000-0000-4000-8000-000000000001',
  buildId: '00000000-0000-4000-8000-000000000002',
  sourceVersionId: '00000000-0000-4000-8000-000000000003',
  url: 'http://localhost:18203/',
}
it('accepts canonical local and server IPv4 ports with complete version identity', () => {
  expect(decodeLocalAddress(address)).toEqual(address)
  expect(decodeLocalAddress({ ...address, url: 'http://127.0.0.1:18204/' }).url).toContain('18204')
  expect(decodeLocalAddress({ ...address, url: 'http://192.0.2.10:28201/' }).url).toContain(
    '28201',
  )
  for (const url of [
    'javascript:alert(1)',
    'http://example.com:18203/',
    'http://localhost:99999/',
    'http://localhost:1000/',
    'http://user@localhost:18203/',
    'http://localhost:18203/api/',
    'http://localhost:18203/?token=secret',
    'http://localhost:18203/#fragment',
    '//localhost:18203/',
    'http://127.0.0.1.evil.test:18203/',
    'http://0.0.0.0:28201/',
    'http://224.1.1.1:28201/',
    'http://127.000.0.1:28201/',
    'http://256.1.1.1:28201/',
  ])
    expect(() => decodeLocalAddress({ ...address, url })).toThrow()
  expect(() => decodeLocalAddress({ ...address, runId: 'wrong' })).toThrow()
})
