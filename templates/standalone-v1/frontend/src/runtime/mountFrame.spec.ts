import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountFrame } from './mountFrame'

const bridge = vi.hoisted(() => ({ start: vi.fn(), dispose: vi.fn() }))
vi.mock('./bridge', () => ({ createRuntimeBridge: vi.fn(() => bridge) }))
const disposers: (() => void)[] = []
function setup() {
  const container = document.createElement('div')
  document.body.append(container)
  const report = vi.fn()
  const dispose = mountFrame(container, vi.fn(), report, '我的应用')
  disposers.push(dispose)
  return { container, report, dispose, frame: container.querySelector('iframe')! }
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
})
afterEach(() => {
  disposers.splice(0).forEach((dispose) => dispose())
  document.body.replaceChildren()
  vi.useRealTimers()
})

describe('standalone frame lifecycle', () => {
  it('uses only the fixed document URL, opaque scripts sandbox and fresh cryptographic channel', () => {
    const first = setup()
    const second = setup()
    expect(first.frame.getAttribute('sandbox')).toBe('allow-scripts')
    expect(first.frame.referrerPolicy).toBe('no-referrer')
    expect(first.frame.title).toBe('我的应用')
    expect(first.frame.getAttribute('src')).toMatch(
      /^\/api\/app\/document#atoms-channel=[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    )
    expect(first.frame.src).not.toBe(second.frame.src)
    expect(bridge.start).not.toHaveBeenCalled()
    first.frame.dispatchEvent(new Event('load'))
    expect(bridge.start).toHaveBeenCalledOnce()
  })

  it('closes the previous channel and removes the frame on unexpected navigation', () => {
    const s = setup()
    s.frame.dispatchEvent(new Event('load'))
    s.frame.dispatchEvent(new Event('load'))
    expect(bridge.start).toHaveBeenCalledOnce()
    expect(bridge.dispose).toHaveBeenCalledOnce()
    expect(s.report).toHaveBeenLastCalledWith('error', expect.stringContaining('发生导航'))
    expect(s.container.childElementCount).toBe(0)
    s.dispose()
    expect(bridge.dispose).toHaveBeenCalledOnce()
  })

  it('bounds loading before any handshake and ignores late load events', async () => {
    const s = setup()
    await vi.advanceTimersByTimeAsync(10000)
    expect(s.report).toHaveBeenLastCalledWith('error', expect.stringContaining('加载超时'))
    expect(bridge.dispose).toHaveBeenCalledOnce()
    s.frame.dispatchEvent(new Event('load'))
    expect(bridge.start).not.toHaveBeenCalled()
    expect(s.container.childElementCount).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('handles load failure and explicit disposal without leaving listeners or timers', () => {
    const s = setup()
    s.frame.dispatchEvent(new Event('error'))
    expect(s.report).toHaveBeenLastCalledWith('error', expect.stringContaining('加载失败'))
    const calls = s.report.mock.calls.length
    s.dispose()
    s.frame.dispatchEvent(new Event('error'))
    expect(s.report).toHaveBeenCalledTimes(calls)
    expect(vi.getTimerCount()).toBe(0)
    expect(s.container.childElementCount).toBe(0)
  })
})
