/** Bounded binary response reader; callers own HTTP/session errors and content semantics. */
export async function readBounded(response: Response, limit: number, signal: AbortSignal) {
  const reader = response.body?.getReader()
  if (!reader) throw new RangeError('Missing response body')
  let complete = false
  try {
    const length = response.headers.get('Content-Length')
    if (length !== null && (!/^\d+$/.test(length) || Number(length) > limit))
      throw new RangeError('Invalid response length')
    const chunks: Uint8Array[] = []
    let size = 0
    while (true) {
      signal.throwIfAborted()
      const { done, value } = await reader.read()
      signal.throwIfAborted()
      if (done) break
      size += value.byteLength
      if (size > limit) throw new RangeError('Response exceeds limit')
      chunks.push(value)
    }
    if (length !== null && Number(length) !== size) throw new RangeError('Truncated response')
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) {
      bytes.set(chunk, offset)
      offset += chunk.length
    }
    complete = true
    return bytes
  } finally {
    // Cancellation can itself reject on a failed stream; preserve the original read error.
    if (!complete) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
