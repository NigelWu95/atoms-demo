import { createRuntimeBridge, type BridgeStatus } from './bridge'
import type { StateDocument, StateRequest } from './protocol'
import { randomId } from './randomId'

/** Both private previews and public apps use the same opaque, capability-limited frame. */
export function mountFrame(
  container: HTMLElement,
  documentUrl: string,
  handle: (request: StateRequest, key: string) => Promise<StateDocument>,
  report: (status: BridgeStatus, message: string) => void,
  title = '应用交互预览',
) {
  const channelId = randomId()
  const frame = document.createElement('iframe')
  frame.title = title
  frame.setAttribute('sandbox', 'allow-scripts')
  frame.referrerPolicy = 'no-referrer'
  let disposed = false
  const notify = (status: BridgeStatus, message: string) => {
    if (!disposed) report(status, message)
  }
  const bridge = createRuntimeBridge(frame, channelId, handle, notify)
  let loaded = false
  frame.addEventListener('load', () => {
    if (disposed) return
    if (loaded) {
      bridge.dispose()
      notify('error', '应用发生导航，旧连接已关闭。请手动重新加载。')
      return
    }
    loaded = true
    bridge.start()
  })
  frame.src = `${documentUrl}#atoms-channel=${channelId}`
  container.replaceChildren(frame)
  return () => {
    disposed = true
    bridge.dispose()
    frame.remove()
  }
}
