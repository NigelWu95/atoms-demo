import { createRuntimeBridge, type BridgeReport, type StateHandler } from './bridge'
import { randomId } from './randomId'

/** A new mount gets a new capability channel; no caller-selectable document or API URL. */
export function mountFrame(
  container: HTMLElement,
  handle: StateHandler,
  report: BridgeReport,
  title: string,
) {
  const channelId = randomId()
  const frame = document.createElement('iframe')
  frame.title = title
  frame.setAttribute('sandbox', 'allow-scripts')
  frame.referrerPolicy = 'no-referrer'
  let disposed = false
  let loaded = false
  const notify: BridgeReport = (status, message) => {
    if (!disposed) report(status, message)
  }
  const bridge = createRuntimeBridge(frame, channelId, handle, notify)
  const timer = setTimeout(() => fail('应用文档加载超时，请重新加载。'), 10000)

  function dispose() {
    if (disposed) return
    disposed = true
    clearTimeout(timer)
    frame.removeEventListener('load', load)
    frame.removeEventListener('error', error)
    bridge.dispose()
    frame.remove()
  }
  function fail(message: string) {
    notify('error', message)
    dispose()
  }
  function load() {
    if (loaded) {
      fail('应用发生导航，旧连接已关闭。请手动重新加载。')
      return
    }
    loaded = true
    clearTimeout(timer)
    bridge.start()
  }
  function error() {
    fail('应用文档加载失败，请重新加载。')
  }
  frame.addEventListener('load', load)
  frame.addEventListener('error', error)
  frame.src = `/api/app/document#atoms-channel=${channelId}`
  container.replaceChildren(frame)
  return dispose
}
