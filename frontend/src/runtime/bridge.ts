import { ApiFailure } from '../api/client'
import { keys, parseRequest, PROTOCOL, type StateDocument, type StateRequest } from './protocol'

export type BridgeStatus = 'connecting' | 'ready' | 'error' | 'saving' | 'saved' | 'unsaved'
export function createRuntimeBridge(
  frame: HTMLIFrameElement,
  channelId: string,
  handle: (request: StateRequest, key: string) => Promise<StateDocument>,
  report: (status: BridgeStatus, message: string) => void,
) {
  let disposed = false
  let started = false
  let port: MessagePort | null = null
  let startupFailed = false
  const pending = new Set<string>()
  let timer: ReturnType<typeof setTimeout> | undefined
  function dispose() {
    if (disposed) return
    disposed = true
    clearTimeout(timer)
    window.removeEventListener('message', hello)
    if (port) {
      port.postMessage({ protocol: PROTOCOL, channelId, type: 'DISPOSE' })
      port.onmessage = null
      port.close()
    }
    pending.clear()
  }
  function timeout() {
    report('error', '预览连接超时或应用未完成启动，请重新加载。')
    dispose()
  }
  async function receive(event: MessageEvent) {
    const request = parseRequest(event.data, channelId)
    if (disposed || !request) return
    const reply = (body: object) => {
      if (!disposed)
        port?.postMessage({
          protocol: PROTOCOL,
          channelId,
          requestId: request.requestId,
          type: 'RESULT',
          ...body,
        })
    }
    if (request.type === 'INVALID_INPUT') {
      reply({
        ok: false,
        error: {
          code: 'INVALID_INPUT',
          message: '状态必须是 256 KiB 以内的 JSON 对象，修订号必须有效。',
        },
      })
      return
    }
    if (request.type === 'RUNTIME_ERROR') {
      startupFailed = true
      clearTimeout(timer)
      report('error', `应用运行错误：${request.payload.message}`)
      return
    }
    if (request.type === 'RUNTIME_READY') {
      clearTimeout(timer)
      if (!startupFailed) report('ready', '应用已启动')
      return
    }
    if (pending.has(request.requestId) || pending.size >= 16) return
    pending.add(request.requestId)
    if (request.type === 'STATE_SAVE') report('saving', '正在保存…')
    try {
      const result = await handle(request, `${channelId}:${request.requestId}`)
      reply({ ok: true, result })
      if (!disposed && request.type === 'STATE_SAVE') report('saved', '已保存到数据库')
    } catch (failure) {
      const error =
        failure instanceof ApiFailure
          ? failure
          : new ApiFailure('BRIDGE_ERROR', '操作未完成，请保留输入并重试。')
      reply({ ok: false, error: { code: error.code, message: error.message.slice(0, 1000) } })
      if (!disposed) report('unsaved', error.message)
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
    port.start()
    // Opaque child requires '*'; send only the per-load channel, never host credentials.
    // https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage
    frame.contentWindow?.postMessage({ protocol: PROTOCOL, channelId, type: 'CONNECT' }, '*', [
      channel.port2,
    ])
    window.removeEventListener('message', hello)
  }
  window.addEventListener('message', hello)
  return {
    start() {
      if (disposed || started) return
      started = true
      report('connecting', '正在连接受限预览…')
      timer = setTimeout(timeout, 10000)
      frame.contentWindow?.postMessage({ protocol: PROTOCOL, channelId, type: 'INIT' }, '*')
    },
    dispose,
  }
}
