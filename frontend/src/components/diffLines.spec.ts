import { describe, expect, it } from 'vitest'
import { diffLines, withContext } from './lineDiff'

describe('bounded line diff', () => {
  it('shows replacements with exact old and new line numbers', () => {
    expect(diffLines('first\nold\nlast', 'first\nnew\nlast')).toEqual({
      limited: false,
      added: 1,
      removed: 1,
      rows: [
        { kind: 'equal', text: 'first', oldLine: 1, newLine: 1 },
        { kind: 'remove', text: 'old', oldLine: 2, newLine: null },
        { kind: 'add', text: 'new', oldLine: null, newLine: 2 },
        { kind: 'equal', text: 'last', oldLine: 3, newLine: 3 },
      ],
    })
  })
  it('handles empty files, repeated lines, Unicode and newlines without normalizing source', () => {
    for (const [before, after] of [
      ['', ''],
      ['', '新增\n'],
      ['删除\n', ''],
      ['a\na\nb', 'a\nb\na'],
      ['a\r\n', 'a\n'],
      [' const x = 1', 'const x = 1'],
    ] as const) {
      const result = diffLines(before, after)
      expect(result.limited).toBe(false)
      expect(
        result.rows
          .filter((row) => row.kind !== 'add')
          .map((row) => row.text)
          .join('\n'),
      ).toBe(before)
      expect(
        result.rows
          .filter((row) => row.kind !== 'remove')
          .map((row) => row.text)
          .join('\n'),
      ).toBe(after)
    }
    expect(diffLines('', '').rows).toEqual([])
    expect(diffLines('a', 'a\n').added).toBe(1)
  })
  it('bounds line count and comparison work but skips common prefixes and suffixes', () => {
    expect(diffLines('a\n'.repeat(2000), '').limited).toBe(true)
    expect(diffLines('a\n'.repeat(600), 'b\n'.repeat(600)).limited).toBe(true)
    const lines = Array.from({ length: 2000 }, (_, i) => `line ${i}`)
    const changed = [...lines]
    changed[1000] = 'changed'
    const result = diffLines(lines.join('\n'), changed.join('\n'))
    expect(result.limited).toBe(false)
    expect(result.added).toBe(1)
    expect(result.removed).toBe(1)
    expect(diffLines('a\n'.repeat(600), 'b\n'.repeat(600)).rows).toEqual([])
  })
  it('keeps three context lines and explicit gap counts around changes', () => {
    const before = Array.from({ length: 20 }, (_, i) => String(i))
    const after = [...before]
    after[10] = 'changed'
    const visible = withContext(diffLines(before.join('\n'), after.join('\n')).rows)
    expect(visible[0]).toEqual({ kind: 'gap', count: 7 })
    expect(visible.at(-1)).toEqual({ kind: 'gap', count: 6 })
    expect(visible.filter((row) => row.kind === 'equal')).toHaveLength(6)
    expect(visible.filter((row) => row.kind === 'add')).toHaveLength(1)
    expect(withContext(diffLines('a\nb', 'a\nb').rows)).toEqual([{ kind: 'gap', count: 2 }])
  })
  it('allows exactly 250000 comparisons and falls back above the budget', () => {
    const before = Array(500).fill('old').join('\n')
    const after = Array(500).fill('new').join('\n')
    expect(diffLines(before, after).limited).toBe(false)
    expect(diffLines(before, after + '\nextra').limited).toBe(true)
  })
})
