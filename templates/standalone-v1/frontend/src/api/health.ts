const messages = {
  offline: '无法连接应用服务。请确认服务已启动、网络可用，然后重试。',
  timeout: '连接超时。请稍后重试，或联系应用维护者检查服务。',
  unavailable: '应用服务暂不可用。请稍后重试；维护者可检查服务与数据库连接。',
  forbidden: '服务拒绝了此访问来源。请通过应用的正确地址访问。',
  invalid: '服务响应格式异常。请联系应用维护者检查部署是否完整。',
}

export class HealthFailure extends Error {
  constructor(readonly kind: keyof typeof messages) {
    super(messages[kind])
  }
}

/** Readiness only: no session creation, credentials, writes or automatic retries. */
export async function checkHealth(signal: AbortSignal): Promise<void> {
  try {
    const response = await fetch('/api/health', {
      method: 'GET',
      mode: 'same-origin',
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      headers: { Accept: 'application/json' },
      signal,
    })
    if (response.status !== 200) {
      await response.body?.cancel()
      throw new HealthFailure(
        response.status >= 500 ? 'unavailable' : response.status === 403 ? 'forbidden' : 'invalid',
      )
    }
    if (response.headers.get('content-type')?.split(';')[0]?.trim() !== 'application/json') {
      await response.body?.cancel()
      throw new HealthFailure('invalid')
    }
    const reader = response.body?.getReader()
    if (!reader) throw new HealthFailure('invalid')
    // Bound even a chunked/misconfigured proxy response. Never display raw response text.
    const bytes: number[] = []
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (bytes.length + value.byteLength > 1024) throw new HealthFailure('invalid')
        bytes.push(...value)
      }
    } finally {
      await reader.cancel()
      reader.releaseLock()
    }
    let body: unknown
    try {
      body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(bytes)))
    } catch {
      throw new HealthFailure('invalid')
    }
    if (
      !body ||
      typeof body !== 'object' ||
      Object.keys(body).length !== 1 ||
      !('status' in body) ||
      body.status !== 'UP'
    )
      throw new HealthFailure('invalid')
  } catch (error) {
    if (error instanceof HealthFailure) throw error
    throw new HealthFailure(
      signal.aborted && signal.reason?.name === 'TimeoutError' ? 'timeout' : 'offline',
    )
  }
}
