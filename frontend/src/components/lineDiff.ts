export type DiffRow = {
  kind: 'equal' | 'add' | 'remove'
  text: string
  oldLine: number | null
  newLine: number | null
}
type DiffResult = {
  limited: boolean
  rows: DiffRow[]
  added: number | null
  removed: number | null
}

export function diffLines(before: string, after: string): DiffResult {
  const a = before === '' ? [] : before.split('\n')
  const b = after === '' ? [] : after.split('\n')
  const fallback: DiffResult = { limited: true, rows: [], added: null, removed: null }
  if (a.length > 2000 || b.length > 2000) return fallback
  let start = 0
  let end = 0
  while (start < a.length && start < b.length && a[start] === b[start]) start++
  while (
    end < a.length - start &&
    end < b.length - start &&
    a[a.length - end - 1] === b[b.length - end - 1]
  )
    end++
  const m = a.length - start - end
  const n = b.length - start - end
  if (m * n > 250000) return fallback
  const width = n + 1
  const lcs = new Uint16Array((m + 1) * width)
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      lcs[i * width + j] =
        a[start + i] === b[start + j]
          ? lcs[(i + 1) * width + j + 1]! + 1
          : Math.max(lcs[(i + 1) * width + j]!, lcs[i * width + j + 1]!)
    }
  }
  const rows: DiffRow[] = []
  let oldIndex = 0
  let newIndex = 0
  function append(kind: DiffRow['kind']) {
    rows.push({
      kind,
      text: (kind === 'add' ? b[newIndex] : a[oldIndex])!,
      oldLine: kind === 'add' ? null : ++oldIndex,
      newLine: kind === 'remove' ? null : ++newIndex,
    })
  }
  while (oldIndex < start) append('equal')
  while (oldIndex < a.length - end || newIndex < b.length - end) {
    const i = oldIndex - start
    const j = newIndex - start
    if (i < m && j < n && a[oldIndex] === b[newIndex]) append('equal')
    else if (i < m && (j === n || lcs[(i + 1) * width + j]! >= lcs[i * width + j + 1]!))
      append('remove')
    else append('add')
  }
  while (oldIndex < a.length) append('equal')
  return {
    limited: false,
    rows,
    added: rows.filter((row) => row.kind === 'add').length,
    removed: rows.filter((row) => row.kind === 'remove').length,
  }
}

export function withContext(rows: DiffRow[]): (DiffRow | { kind: 'gap'; count: number })[] {
  const visible = new Set<number>()
  rows.forEach((row, index) => {
    if (row.kind !== 'equal') {
      for (let i = Math.max(0, index - 3); i <= Math.min(rows.length - 1, index + 3); i++)
        visible.add(i)
    }
  })
  const result: ReturnType<typeof withContext> = []
  let hidden = 0
  rows.forEach((row, index) => {
    if (!visible.has(index)) hidden++
    else {
      if (hidden) result.push({ kind: 'gap', count: hidden })
      hidden = 0
      result.push(row)
    }
  })
  if (hidden) result.push({ kind: 'gap', count: hidden })
  return result
}
