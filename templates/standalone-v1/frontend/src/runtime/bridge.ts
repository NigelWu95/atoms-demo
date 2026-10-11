import { ApiFailure } from '../api/client'
import { keys } from '../api/responses'
import { parseRequest, PROTOCOL, type StateDocument, type StateRequest } from './protocol'

export type BridgeStatus = 'connecting' | 'ready' | 'error' | 'saving' | 'saved' | 'unsaved'
export type StateHandler = (request: StateRequest, key: string) => Promise<StateDocument>
export type BridgeReport = (status: BridgeStatus, message: string) => void

export function createRuntimeBridge(
  frame: HTMLIFrameElement,
  channelId: string,
  handle: StateHandler,
  report: BridgeReport,
) {
  let disposed = false
  let started = false
  let port: MessagePort | null = null
  let runtimeFailed = false
  let dataFailed = false
  const pending = new Set<string>()
  let timer: ReturnType<typeof setTimeout> | undefined

  function dispose() {
    if (disposed) return
    disposed = true
    clearTimeout(timer)
    window.removeEventListener('message', hello)
    if (port) {
      try {
        port.postMessage({ protocol: PROTOCOL, channelId, type: 'DISPOSE' })
      } catch {
        /* A broken transport must not prevent closing the port. */
      }
      port.onmessage = null
      port.onmessageerror = null
      port.close()
    }
    pending.clear()
  }
  function fail(message: string) {
    if (disposed) return
    report('error', message)
    dispose()
  }
  async function receive(event: MessageEvent) {
    if (disposed) return
    const request = parseRequest(event.data, channelId)
    if (!request) return
    const reply = (body: object) => {
      if (disposed) return
      try {
        port?.postMessage({
          protocol: PROTOCOL,
          channelId,
          requestId: request.requestId,
          type: 'RESULT',
          ...body,
        })
      } catch {
        fail('应用通信失败，请重新加载。')
      }
    }
    if (request.type === 'INVALID_INPUT') {
      reply({
        ok: false,
        error: {
          code: 'INVALID_INPUT',
          message: '状态必须是 256 KiB 以内、嵌套不超过 63 层的 JSON 对象，修订号必须有效。',
        },
      })
      return
    }
    if (request.type === 'RUNTIME_ERROR') {
      runtimeFailed = true
      clearTimeout(timer)
      report('error', `应用运行错误：${request.payload.message}`)
      return
    }
    if (request.type === 'RUNTIME_READY') {
      clearTimeout(timer)
      if (!runtimeFailed && !dataFailed) report('ready', '应用已启动')
      return
    }
    if (pending.has(request.requestId) || pending.size >= 16) return
    pending.add(request.requestId)
    if (request.type === 'STATE_SAVE' && !runtimeFailed) report('saving', '正在保存…')
    try {
      const result = await handle(request, `${channelId}:${request.requestId}`)
      reply({ ok: true, result })
      if (!disposed && !runtimeFailed && request.type === 'STATE_SAVE') {
        dataFailed = false
        report('saved', '已保存到数据库')
      }
    } catch (failure) {
      dataFailed = true
      const error = failure instanceof ApiFailure ? failure : new ApiFailure('BRIDGE_ERROR')
      reply({ ok: false, error: { code: error.code, message: error.message.slice(0, 1000) } })
      if (!disposed && !runtimeFailed) report('unsaved', error.message)
    } finally {
      pending.delete(request.requestId)
    }
  }
  function hello(event: MessageEvent) {
    if (
      disposed ||
      !started ||
      port ||
      event.source !== frame.contentWindow ||
      event.origin !== 'null' ||
      !keys(event.data, ['protocol', 'channelId', 'type']) ||
      event.data.protocol !== PROTOCOL ||
      event.data.channelId !== channelId ||
      event.data.type !== 'HELLO'
    )
      return
    const channel = new MessageChannel()
    port = channel.port1
    port.onmessage = (message) => {
      void receive(message)
    }
    port.onmessageerror = () => fail('应用通信失败，请重新加载。')
    port.start()
    try {
      // Opaque child requires '*'; only a per-load channel and capability port cross this boundary.
      // https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage
      frame.contentWindow?.postMessage({ protocol: PROTOCOL, channelId, type: 'CONNECT' }, '*', [
        channel.port2,
      ])
    } catch {
      channel.port2.close()
      fail('应用通信失败，请重新加载。')
    }
    window.removeEventListener('message', hello)
  }
  window.addEventListener('message', hello)
  return {
    start() {
      if (disposed || started) return
      started = true
      report('connecting', '正在连接应用沙箱…')
      timer = setTimeout(() => fail('应用连接超时或未完成启动，请重新加载。'), 10000)
      try {
        frame.contentWindow?.postMessage({ protocol: PROTOCOL, channelId, type: 'INIT' }, '*')
      } catch {
        fail('应用通信失败，请重新加载。')
      }
    },
    dispose,
  }
}
