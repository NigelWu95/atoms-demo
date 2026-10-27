import { expect, it } from 'vitest'
import { decodeFile, decodeTree, decodeVersions, uuid } from './projectWorkbench'
import { decodePreview } from './projectDelivery'
it('rejects oversized or malformed source responses and does not spread private metadata', () => {
  expect(() =>
    decodeFile({
      path: 'README.md',
      mode: 33188,
      bytes: 3,
      content: 'a'.repeat(262145),
      truncated: false,
    }),
  ).toThrow()
  expect(() => decodeTree(Array.from({ length: 513 }, () => ({})))).toThrow()
  expect(() => decodeVersions({ items: [], nextBeforeVersion: -1 })).toThrow()
  expect(() => uuid('1-1-1-1-1')).toThrow()
  expect(
    decodeFile({
      path: 'README.md',
      mode: 33188,
      bytes: 3,
      content: 'foo',
      truncated: false,
      privateRoot: '/hidden',
    }),
  ).not.toHaveProperty('privateRoot')
})
it('preview decoder accepts only the fixed local token route', () => {
  const id = '00000000-0000-4000-8000-000000000001'
  const response = {
    previewId: id,
    expiresAt: new Date().toISOString(),
    iframeUrl: 'https://other.example/',
    identity: {
      runId: id,
      build: { projectId: id, buildId: id, sourceVersionId: id, treeSha256: 'a'.repeat(64) },
    },
  }
  expect(() => decodePreview(response)).toThrow()
  expect(() =>
    decodePreview({ ...response, iframeUrl: '/api/delivery/preview/../admin/' }),
  ).toThrow()
  expect(
    decodePreview({ ...response, iframeUrl: `/api/delivery/preview/${'a'.repeat(43)}/` }).identity
      .sourceVersionId,
  ).toBe(id)
})
