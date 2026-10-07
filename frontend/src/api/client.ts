export class ApiFailure extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status = 0,
    public readonly requestId: string | null = null,
    public readonly retryable = false,
  ) {
    super(message)
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
export function protocolFailure(): never {
  throw new ApiFailure('INVALID_RESPONSE', '服务响应格式异常，请稍后重试。')
}
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiFailure)) return '操作未完成，请重试。'
  return error.requestId ? `${error.message}（请求编号：${error.requestId}）` : error.message
}
type RequestOptions = {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  body?: unknown
  idempotencyKey?: string
}
export type Csrf = { token: string; headerName: string }

export function createApiClient() {
  let csrf: Csrf | null = null
  let onSessionFailure: ((error: ApiFailure) => void) | undefined
  async function request<T>(
    path: string,
    decode: (value: unknown) => T,
    options: RequestOptions = {},
  ): Promise<T> {
    const method = options.method ?? 'GET'
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      if (!csrf) throw new ApiFailure('SESSION_REQUIRED', '请先连接访客会话。', 401)
      headers[csrf.headerName] = csrf.token
      headers['Content-Type'] = 'application/json'
    }
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
    let response: Response
    try {
      response = await fetch(path, {
        method,
        headers,
        credentials: 'same-origin',
        cache: 'no-store',
        signal: AbortSignal.timeout(10000),
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      })
    } catch {
      throw new ApiFailure(
        'NETWORK_ERROR',
        '连接中断，操作结果尚不确定。请保留输入并重试。',
        0,
        null,
        true,
      )
    }
    if (response.status === 204) return decode(undefined)
    let body: unknown
    try {
      body = await response.json()
    } catch {
      if (response.ok) return protocolFailure()
      body = null
    }
    if (!response.ok) {
      const structured = isRecord(body) && isRecord(body.error) ? body.error : null
      const error = new ApiFailure(
        structured && typeof structured.code === 'string' ? structured.code : 'HTTP_ERROR',
        structured && typeof structured.message === 'string'
          ? structured.message
          : `请求未完成（${response.status}）。`,
        response.status,
        isRecord(body) && typeof body.requestId === 'string' ? body.requestId : null,
        structured?.retryable === true,
      )
      if ([401, 403].includes(response.status)) {
        csrf = null
        onSessionFailure?.(error)
      }
      throw error
    }
    return decode(body)
  }
  return {
    request,
    setCsrf: (value: Csrf) => {
      csrf = value
    },
    onSessionFailure: (handler: (error: ApiFailure) => void) => {
      onSessionFailure = handler
    },
  }
}

export const api = createApiClient()
