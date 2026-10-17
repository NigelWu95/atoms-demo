import { expect, it } from 'vitest'
import { accountReturnTarget } from './accountReturn'

const id = '11111111-1111-4111-8111-111111111111'
const version = '22222222-2222-4222-8222-222222222222'
it.each(['/', '/projects', '/workbench'])('accepts the internal destination %s', (path) => {
  expect(accountReturnTarget(path)).toEqual({ path, projectId: null })
})
it.each([`/projects/${id}`, `/preview/${id}/${version}`])(
  'identifies the project to authorize for %s',
  (path) => expect(accountReturnTarget(path)).toEqual({ path, projectId: id }),
)
it.each([
  undefined,
  null,
  '',
  ['/', '/projects'],
  'https://evil.example',
  '//evil.example',
  '/\\evil.example',
  'javascript:alert(1)',
  '/%2f%2fevil.example',
  '/account',
  '/account?returnTo=/',
  '/api/session',
  '/a/public',
  '/projects?next=//evil.example',
  '/projects#fragment',
  `/projects/${id}/..`,
  '/projects/not-a-uuid',
  `/preview/${id}/bad`,
  '/projects\n',
])('rejects untrusted, recursive or unsupported destinations: %j', (value) => {
  expect(accountReturnTarget(value)).toBeNull()
})
