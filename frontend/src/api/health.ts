export async function checkHealth(): Promise<void> {
  const response = await fetch('/api/health', {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(5000),
    cache: 'no-store',
  })
  if (!response.ok) throw new Error('服务暂不可用')
  const body: unknown = await response.json()
  if (!body || typeof body !== 'object' || !('status' in body) || body.status !== 'UP') {
    throw new Error('服务响应格式异常')
  }
}
