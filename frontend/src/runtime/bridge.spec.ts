import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRuntimeBridge } from './bridge'

class TestPort {
  onmessage: ((event: MessageEvent) => void) | null = null
  postMessage = vi.fn()
  close = vi.fn()
  start = vi.fn()
  receive(data: unknown) {
    this.onmessage?.(new MessageEvent('message', { data }))
  }
}
const ports: TestPort[] = []
class TestChannel {
  port1 = new TestPort()
  port2 = new TestPort()
  constructor() {
    ports.push(this.port1, this.port2)
  }
}
const channelId = 'e1de3855-413f-4884-8e77-16e622511233'
const requestId = 'a9319861-144e-46ea-aed6-c081e763b1d8'
const state = { value: { count: 1 }, revision: 1, stateSchemaVersion: 1 }
const disposers: (() => void)[] = []
function setup() {
  vi.stubGlobal('MessageChannel', TestChannel)
  const frame = document.createElement('iframe')
  document.body.append(frame)
  const post = vi.spyOn(frame.contentWindow!, 'postMessage').mockImplementation(() => {})
  const handler = vi.fn().mockResolvedValue(state)
  const report = vi.fn()
  const bridge = createRuntimeBridge(frame, channelId, handler, report)
  disposers.push(bridge.dispose)
  bridge.start()
  function hello(ch = channelId, source: Window | null = frame.contentWindow) {
    window.dispatchEvent(
      new MessageEvent('message', {
        source,
        origin: 'null',
        data: { protocol: 'atoms-runtime-v1', type: 'HELLO', channelId: ch },
      }),
    )
  }
  return { frame, post, handler, report, bridge, hello }
}
function rpc(type = 'STATE_GET', payload: unknown = {}, channel = channelId) {
  return { protocol: 'atoms-runtime-v1', channelId: channel, requestId, type, payload }
}
afterEach(() => {
  disposers.splice(0).forEach((dispose) => dispose())
  document.body.replaceChildren()
  ports.length = 0
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
describe('private runtime bridge', () => {
  it('handles startup and errors even when the data queue is full', () => {
    const s = setup()
    s.hello()
    s.handler.mockImplementation(() => new Promise(() => {}))
    const port = ports[0]!
    for (let i = 0; i < 16; i++) port.receive({ ...rpc(), requestId: crypto.randomUUID() })
    port.receive({ ...rpc('RUNTIME_READY'), requestId: crypto.randomUUID() })
    expect(s.report).toHaveBeenCalledWith('ready', '应用已启动')
    port.receive({
      ...rpc('RUNTIME_ERROR', { message: 'test failure' }),
      requestId: crypto.randomUUID(),
    })
    expect(s.report).toHaveBeenCalledWith('error', '应用运行错误：test failure')
  })
  it('requires exact frame and channel, transfers once, exposes no credentials', () => {
    const s = setup()
    s.hello(channelId, window)
    s.hello('wrong')
    expect(ports).toHaveLength(0)
    expect(s.post).toHaveBeenCalledWith(
      { protocol: 'atoms-runtime-v1', type: 'INIT', channelId },
      '*',
    )
    s.hello()
    s.hello()
    expect(ports).toHaveLength(2)
    expect(s.post).toHaveBeenCalledTimes(2)
  })
  it('ignores wrong type, channel, extra authority and invalid JSON before invoking handler', async () => {
    const s = setup()
    s.hello()
    const port = ports[0]!
    port.receive(rpc('FETCH'))
    port.receive(rpc('STATE_GET', {}, 'old'))
    port.receive(rpc('STATE_GET', { projectId: 'other' }))
    port.receive(rpc('STATE_SAVE', { expectedRevision: 0, value: new Date() }))
    port.receive(rpc('STATE_SAVE', { expectedRevision: 0, value: { tooBig: '界'.repeat(90000) } }))
    expect(s.handler).not.toHaveBeenCalled()
    expect(port.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        ok: false,
        error: expect.objectContaining({ code: 'INVALID_INPUT' }),
      }),
    )
    port.postMessage.mockClear()
    port.receive(rpc('STATE_SAVE', { expectedRevision: 0, value: { count: 1 } }))
    await vi.waitFor(() => expect(port.postMessage).toHaveBeenCalled())
    expect(s.handler.mock.calls[0]?.[1]).toBe(`${channelId}:${requestId}`)
    expect(port.postMessage.mock.calls[0]?.[0]).toMatchObject({
      requestId,
      ok: true,
      result: state,
    })
  })
  it('closes a disposed port and ignores late async results', async () => {
    const s = setup()
    s.hello()
    let finish: (value: unknown) => void = () => {}
    s.handler.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const port = ports[0]!
    port.receive(rpc())
    s.bridge.dispose()
    finish(state)
    await Promise.resolve()
    await Promise.resolve()
    expect(port.close).toHaveBeenCalledOnce()
    expect(port.postMessage).toHaveBeenCalledWith(expect.objectContaining({ type: 'DISPOSE' }))
    expect(port.postMessage.mock.calls.some(([value]) => value?.ok === true)).toBe(false)
  })
  it('reports handshake timeout without calling a state API', async () => {
    vi.useFakeTimers()
    const s = setup()
    await vi.advanceTimersByTimeAsync(10000)
    expect(s.report).toHaveBeenCalledWith('error', expect.stringContaining('连接超时'))
    expect(s.handler).not.toHaveBeenCalled()
    s.hello()
    expect(ports).toHaveLength(0)
  })
})
