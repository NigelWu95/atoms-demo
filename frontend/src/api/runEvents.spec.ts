import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { subscribeToRun } from './runEvents'

class Source extends EventTarget {
  static instances: Source[] = []
  readyState = 1
  close = vi.fn(() => {
    this.readyState = 2
  })
  constructor(readonly url: string) {
    super()
    Source.instances.push(this)
  }
  send(type: string, seq: number, data: Record<string, unknown> = {}) {
    this.dispatchEvent(
      new MessageEvent(type, {
        lastEventId: String(seq),
        data: JSON.stringify({ runId: 'run-1', seq, createdAt: '2026-10-07T00:00:00Z', ...data }),
      }),
    )
  }
}
beforeEach(() => {
  Source.instances = []
  vi.stubGlobal('EventSource', Source)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
function setup(after = 1) {
  const handlers = { event: vi.fn(), state: vi.fn(), error: vi.fn() }
  const stop = subscribeToRun('run-1', after, handlers)
  return { source: Source.instances[0]!, handlers, stop }
}
it('uses the snapshot cursor, deduplicates replay and closes after a complete terminal event', () => {
  const { source, handlers } = setup()
  expect(source.url).toBe('/api/runs/run-1/events?after=1')
  source.dispatchEvent(new Event('open'))
  source.send('run.queued', 1)
  source.send('stage.started', 2, { stage: 'BUILDING' })
  source.send('stage.started', 2, { stage: 'BUILDING' })
  source.send('run.completed', 3, { status: 'SUCCEEDED', versionId: 'v2', lastEventSeq: 3 })
  expect(handlers.event.mock.calls.map(([event]) => event.seq)).toEqual([2, 3])
  expect(handlers.state).toHaveBeenCalledWith('connected')
  expect(source.close).toHaveBeenCalledOnce()
  expect(handlers.error).not.toHaveBeenCalled()
})
it.each([
  ['stage.started', 3, { stage: 'BUILDING' }],
  ['stage.started', 2, { stage: 'private-value' }],
  ['stage.started', 2, { stage: 'BUILDING', runId: 'other-run' }],
  ['run.completed', 2, { status: 'SUCCEEDED', versionId: '', lastEventSeq: 2 }],
  ['run.completed', 2, { status: 'FAILED', lastEventSeq: 3 }],
] as const)('stops on a gap or malformed %s event', (type, seq, data) => {
  const { source, handlers } = setup()
  source.send(type, seq, data)
  expect(source.close).toHaveBeenCalledOnce()
  expect(handlers.error).toHaveBeenCalledOnce()
  expect(handlers.event).not.toHaveBeenCalled()
  expect(handlers.error.mock.calls[0]![0]).not.toContain('private-value')
})
it('allows native reconnect with its event ID but stops after three consecutive failures', () => {
  const { source, handlers } = setup()
  source.readyState = 0
  source.dispatchEvent(new Event('error'))
  expect(handlers.state).toHaveBeenLastCalledWith('reconnecting')
  source.dispatchEvent(new Event('error'))
  expect(source.close).not.toHaveBeenCalled()
  source.dispatchEvent(new Event('error'))
  expect(source.close).toHaveBeenCalledOnce()
  expect(handlers.state).toHaveBeenLastCalledWith('paused')
})
it('cleans up on navigation and ignores late callbacks', () => {
  const { source, handlers, stop } = setup()
  stop()
  source.send('stage.started', 2, { stage: 'BUILDING' })
  source.dispatchEvent(new Event('error'))
  expect(handlers.event).not.toHaveBeenCalled()
  expect(handlers.error).not.toHaveBeenCalled()
})
it('bounds rapid open/close failures instead of resetting the retry limit on every open', () => {
  vi.spyOn(performance, 'now').mockReturnValue(0)
  const { source, handlers } = setup()
  for (let attempt = 0; attempt < 3; attempt++) {
    source.dispatchEvent(new Event('open'))
    source.readyState = 0
    source.dispatchEvent(new Event('error'))
  }
  expect(source.close).toHaveBeenCalledOnce()
  expect(handlers.state).toHaveBeenLastCalledWith('paused')
  expect(handlers.event).not.toHaveBeenCalled()
})
it('does not exhaust retries when healthy idle connections rotate without new events', () => {
  let now = 0
  vi.spyOn(performance, 'now').mockImplementation(() => now)
  const { source, handlers } = setup()
  for (let rotation = 0; rotation < 4; rotation++) {
    source.dispatchEvent(new Event('open'))
    now += 60000
    source.readyState = 0
    source.dispatchEvent(new Event('error'))
  }
  expect(source.close).not.toHaveBeenCalled()
  expect(handlers.state).toHaveBeenLastCalledWith('reconnecting')
  expect(handlers.error).not.toHaveBeenCalled()
})
