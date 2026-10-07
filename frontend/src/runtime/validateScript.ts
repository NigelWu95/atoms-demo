import { parse } from 'acorn'

/** Parse only; never eval generated code or loosen the host's CSP. */
export function validateScript(source: string): string | null {
  if (new TextEncoder().encode(source).length > 120 * 1024) return '脚本大小超限，未执行。'
  try {
    parse(source, { ecmaVersion: 2022, sourceType: 'script' })
    return null
  } catch {
    // Parser diagnostics may contain source fragments; do not echo them.
    return 'JavaScript 语法检查未通过，未执行。请查看 main.js 源码。'
  }
}
