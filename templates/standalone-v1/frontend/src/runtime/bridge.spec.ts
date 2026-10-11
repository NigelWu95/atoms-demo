import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiFailure } from '../api/client'
import { createRuntimeBridge } from './bridge'

class TestPort {
  onmessage: ((event: MessageEvent) => void) | null = null
  onmessageerror: (() => void) | null = null
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
  function hello(
    channel = channelId,
    source: Window | null = frame.contentWindow,
    origin = 'null',
  ) {
    window.dispatchEvent(
      new MessageEvent('message', {
        source,
        origin,
        data: { protocol: 'atoms-runtime-v1', type: 'HELLO', channelId: channel },
      }),
    )
  }
  return { bridge, hello, post, handler, report }
}
function rpc(type = 'STATE_GET', payload: unknown = {}, channel = channelId) {
  return { protocol: 'atoms-runtime-v1', channelId: channel, requestId, type, payload }
}
afterEach(() => {
  disposers.splice(0).forEach((dispose) => dispose())
  document.body.replaceChildren()
  ports.length = 0
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('standalone runtime bridge', () => {
  it('requires startup, exact opaque frame and channel; transfers one port and no credentials', () => {
    const s = setup()
    s.hello()
    expect(ports).toHaveLength(0)
    s.bridge.start()
    s.bridge.start()
    s.hello(channelId, window)
    s.hello('old')
    s.hello(channelId, null)
    s.hello(channelId, window.frames[0]!, location.origin)
    expect(ports).toHaveLength(0)
    expect(s.post).toHaveBeenCalledWith(
      { protocol: 'atoms-runtime-v1', type: 'INIT', channelId },
      '*',
    )
    s.hello()
    s.hello()
    expect(ports).toHaveLength(2)
    expect(s.post).toHaveBeenLastCalledWith(
      { protocol: 'atoms-runtime-v1', type: 'CONNECT', channelId },
      '*',
      [ports[1]],
    )
    expect(s.post).toHaveBeenCalledTimes(2)
  })

  it('rejects invalid messages and routes valid state requests with a stable intent key', async () => {
    const s = setup()
    s.bridge.start()
    s.hello()
    const port = ports[0]!
    port.receive(rpc('FETCH'))
    port.receive(rpc('STATE_GET', {}, 'old'))
    port.receive(rpc('STATE_GET', { projectId: 'other' }))
    port.receive(rpc('STATE_SAVE', { expectedRevision: 0, value: new Date() }))
    expect(s.handler).not.toHaveBeenCalled()
    expect(port.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        ok: false,
        error: expect.objectContaining({ code: 'INVALID_INPUT' }),
      }),
    )
    port.receive(rpc('STATE_SAVE', { expectedRevision: 0, value: { count: 1 } }))
    await flushPromises()
    expect(s.handler).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'STATE_SAVE' }),
      `${channelId}:${requestId}`,
    )
    expect(port.postMessage).toHaveBeenLastCalledWith(
      expect.objectContaining({ requestId, ok: true, result: state }),
    )
  })

  it('bounds pending data calls but still processes ready and error events', () => {
    const s = setup()
    s.bridge.start()
    s.hello()
    s.handler.mockImplementation(() => new Promise(() => {}))
    const port = ports[0]!
    port.receive(rpc())
    port.receive(rpc())
    for (let i = 0; i < 17; i++) port.receive({ ...rpc(), requestId: crypto.randomUUID() })
    expect(s.handler).toHaveBeenCalledTimes(16)
    port.receive(rpc('RUNTIME_READY'))
    expect(s.report).toHaveBeenLastCalledWith('ready', '应用已启动')
    port.receive(rpc('RUNTIME_ERROR', { message: '<b>generated failure</b>' }))
    port.receive(rpc('RUNTIME_READY'))
    expect(s.report).toHaveBeenLastCalledWith('error', '应用运行错误：<b>generated failure</b>')
  })

  it('does not turn unavailable storage into successful readiness or saved data', async () => {
    const s = setup()
    s.bridge.start()
    s.hello()
    s.handler.mockRejectedValue(new ApiFailure('READ_ONLY_PREVIEW'))
    ports[0]!.receive(rpc())
    await flushPromises()
    ports[0]!.receive(rpc('RUNTIME_READY'))
    expect(s.report).toHaveBeenLastCalledWith('unsaved', expect.stringContaining('不能读取或保存'))
    expect(ports[0]!.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        ok: false,
        error: expect.objectContaining({ code: 'READ_ONLY_PREVIEW' }),
      }),
    )
    s.handler.mockRejectedValue(new Error('PRIVATE_UPSTREAM'))
    ports[0]!.receive(rpc('STATE_SAVE', { expectedRevision: 0, value: {} }))
    await flushPromises()
    expect(JSON.stringify(s.report.mock.calls)).not.toContain('PRIVATE_UPSTREAM')
    expect(s.report).not.toHaveBeenCalledWith('saved', expect.anything())
  })

  it('closes on disposal and ignores late async work even when DISPOSE cannot be sent', async () => {
    const s = setup()
    s.bridge.start()
    s.hello()
    let finish!: (value: unknown) => void
    s.handler.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const port = ports[0]!
    port.receive(rpc())
    port.postMessage.mockImplementation(() => {
      throw new Error('closed transport')
    })
    s.bridge.dispose()
    const reports = s.report.mock.calls.length
    finish(state)
    await flushPromises()
    expect(port.close).toHaveBeenCalledOnce()
    expect(port.onmessage).toBeNull()
    expect(port.onmessageerror).toBeNull()
    expect(s.report).toHaveBeenCalledTimes(reports)
    s.bridge.dispose()
    expect(port.close).toHaveBeenCalledOnce()
  })

  it('distinguishes unknown writes, conflicts and offline reads; late READY cannot hide them', async () => {
    const s = setup()
    s.bridge.start()
    s.hello()
    const port = ports[0]!
    for (const [failure, expected] of [
      [new ApiFailure('NETWORK_ERROR', 0, undefined, true), 'unknown'],
      [new ApiFailure('INVALID_RESPONSE', 0, undefined, true), 'unknown'],
      [new ApiFailure('STATE_CONFLICT', 409), 'conflict'],
      [new ApiFailure('STALE_APP_VERSION', 409), 'conflict'],
    ] as const) {
      s.handler.mockRejectedValueOnce(failure)
      port.receive(rpc('STATE_SAVE', { value: {}, expectedRevision: 0 }))
      expect(s.report).toHaveBeenLastCalledWith('saving', expect.any(String))
      await flushPromises()
      port.receive(rpc('RUNTIME_READY'))
      expect(s.report).toHaveBeenLastCalledWith(expected, expect.any(String))
    }
    s.handler.mockRejectedValueOnce(new ApiFailure('SERVICE_UNAVAILABLE', 503))
    port.receive(rpc())
    await flushPromises()
    expect(s.report).toHaveBeenLastCalledWith('offline', expect.any(String))
    port.receive(rpc('STATE_SAVE', { value: {}, expectedRevision: 0 }))
    await flushPromises()
    expect(s.report).toHaveBeenLastCalledWith('saved', '已保存到数据库')
  })

  it('times out without allowing late hello or state access', async () => {
    vi.useFakeTimers()
    const s = setup()
    s.bridge.start()
    await vi.advanceTimersByTimeAsync(10000)
    expect(s.report).toHaveBeenLastCalledWith('error', expect.stringContaining('连接超时'))
    s.hello()
    expect(ports).toHaveLength(0)
    expect(s.handler).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('closes broken channels with a safe error', () => {
    const s = setup()
    s.bridge.start()
    s.hello()
    ports[0]!.onmessageerror?.()
    expect(ports[0]!.close).toHaveBeenCalledOnce()
    expect(s.report).toHaveBeenLastCalledWith('error', expect.stringContaining('通信失败'))
  })
})
