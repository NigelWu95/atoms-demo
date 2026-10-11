import {
  appDescription,
  csrfToken,
  guestIdentity,
  InvalidResponse,
  keys,
  readJson,
  UUID,
  type AppDescription,
} from './responses'

const messages = {
  SESSION_REQUIRED: '访客会话已失效，请重新连接。',
  CSRF_REJECTED: '安全验证失败，请重新连接后重试。',
  FORBIDDEN: '服务拒绝了此访问来源或权限，请确认应用访问地址。',
  SERVICE_UNAVAILABLE: '应用服务暂不可用，请稍后重试。',
  INTERNAL_ERROR: '服务处理失败，请稍后重试。',
  NOT_FOUND: '应用接口不存在，请检查部署是否完整。',
  INVALID_RESPONSE: '服务响应格式异常，请联系应用维护者。',
  NETWORK_ERROR: '网络连接中断，请检查网络后手动重试。',
  TIMEOUT: '连接超时，请稍后手动重试。',
  CANCELLED: '连接已取消。',
  READ_ONLY_PREVIEW: '当前版本尚未接入数据服务，不能读取或保存应用数据。',
  BRIDGE_ERROR: '应用操作未完成，请保留输入后重试。',
  STATE_CONFLICT: '数据已更新，请先读取最新数据再修改；本次保存未覆盖数据。',
  STALE_APP_VERSION: '应用版本已更新，请保留输入后重新加载。',
  IDEMPOTENCY_PAYLOAD_MISMATCH: '同一请求标识不能用于不同内容。',
  INVALID_INPUT: '请求格式或内容不正确，本次保存未提交。',
  PAYLOAD_TOO_LARGE: '请求体超过大小限制，本次保存未提交。',
}
type FailureCode = keyof typeof messages
export class ApiFailure extends Error {
  constructor(
    readonly code: FailureCode,
    readonly status = 0,
    readonly requestId?: string,
    readonly writeUnknown = false,
  ) {
    super(messages[code])
  }
}

export function responseFailure(body: unknown, status: number): ApiFailure {
  const codes: Record<string, number> = {
    SESSION_REQUIRED: 401,
    CSRF_REJECTED: 403,
    FORBIDDEN: 403,
    NOT_FOUND: 404,
    INTERNAL_ERROR: 500,
    SERVICE_UNAVAILABLE: 503,
    STATE_CONFLICT: 409,
    STALE_APP_VERSION: 409,
    IDEMPOTENCY_PAYLOAD_MISMATCH: 409,
    INVALID_INPUT: 422,
    PAYLOAD_TOO_LARGE: 413,
  }
  if (
    keys(body, ['error', 'requestId']) &&
    keys(body.error, ['code', 'message', 'retryable']) &&
    typeof body.error.code === 'string' &&
    Object.hasOwn(codes, body.error.code) &&
    codes[body.error.code] === status &&
    typeof body.error.message === 'string' &&
    body.error.message.length <= 1000 &&
    body.error.retryable === status >= 500 &&
    typeof body.requestId === 'string' &&
    body.requestId.length === 36 &&
    UUID.test(body.requestId)
  )
    return new ApiFailure(body.error.code as FailureCode, status, body.requestId)
  return new ApiFailure('INVALID_RESPONSE', status)
}

export async function freshCsrf(signal: AbortSignal): Promise<string> {
  return csrfToken(await request('/api/csrf', signal))
}

async function request(
  path: '/api/csrf' | '/api/session' | '/api/app',
  signal: AbortSignal,
  token?: string,
): Promise<unknown> {
  signal.throwIfAborted()
  const response = await fetch(path, {
    method: token ? 'POST' : 'GET',
    credentials: 'same-origin',
    mode: 'same-origin',
    redirect: 'error',
    cache: 'no-store',
    signal,
    headers: {
      Accept: 'application/json',
      ...(token ? { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token } : {}),
    },
    ...(token ? { body: '{}' } : {}),
  })
  const body = await readJson(response)
  if (response.status >= 400) throw responseFailure(body, response.status)
  if (response.status !== 200) throw new InvalidResponse()
  return body
}

/** One explicit attempt. Tokens stay in this call's memory; never persisted or sent to the frame. */
export async function initializeApp(signal: AbortSignal): Promise<AppDescription> {
  try {
    const token = csrfToken(await request('/api/csrf', signal))
    guestIdentity(await request('/api/session', signal, token))
    // Authentication clears the old token. Validate a fresh token before continuing.
    // https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html#csrf-integration-javascript
    csrfToken(await request('/api/csrf', signal))
    return appDescription(await request('/api/app', signal))
  } catch (error) {
    if (error instanceof ApiFailure) throw error
    if (signal.aborted)
      throw new ApiFailure(signal.reason?.name === 'TimeoutError' ? 'TIMEOUT' : 'CANCELLED')
    throw new ApiFailure(error instanceof InvalidResponse ? 'INVALID_RESPONSE' : 'NETWORK_ERROR')
  }
}
