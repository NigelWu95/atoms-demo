import { expect, it } from 'vitest'
import { validateScript } from './validateScript'

it('parses a classic script without executing it', () => {
  expect(
    validateScript('throw new Error("must not execute"); Atoms.ready(async () => {});'),
  ).toBeNull()
})
it.each(['const broken = ;', 'return 1;', 'export default 1;', 'await Promise.resolve();'])(
  'rejects invalid classic script syntax: %s',
  (source) => expect(validateScript(source)).toMatch(/语法检查未通过/),
)
it('limits source size and does not echo source text from syntax errors', () => {
  expect(validateScript('界'.repeat(50000))).toContain('大小超限')
  expect(validateScript('const private_value = ;')).not.toContain('private_value')
})
