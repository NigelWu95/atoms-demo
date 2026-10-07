import { isRecord } from './client'

export type StreamState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'paused'
export type RunEvent =
  | { type: 'stage.started'; seq: number; stage: string }
  | { type: 'run.completed'; seq: number; lastEventSeq: number }
  | { type: 'run.queued' | 'plan.ready' | 'artifact.created'; seq: number }
export type RunEventHandlers = {
  event: (event: RunEvent) => void
  state: (state: StreamState) => void
  error: (message: string) => void
}

function decode(runId: string, type: RunEvent['type'], event: MessageEvent<string>): RunEvent {
  if (typeof event.data !== 'string' || event.data.length > 131072) throw new Error('Invalid event')
  const value: unknown = JSON.parse(event.data)
  if (
    !isRecord(value) ||
    value.runId !== runId ||
    typeof value.seq !== 'number' ||
    !Number.isSafeInteger(value.seq) ||
    value.seq < 1 ||
    String(value.seq) !== event.lastEventId ||
    typeof value.createdAt !== 'string' ||
    !Number.isFinite(Date.parse(value.createdAt))
  )
    throw new Error('Invalid event')
  if (type === 'stage.started') {
    if (
      typeof value.stage !== 'string' ||
      !['PLANNING', 'BUILDING', 'REPAIRING'].includes(value.stage)
    )
      throw new Error('Invalid stage')
    return { type, seq: value.seq, stage: value.stage }
  }
  if (type === 'run.completed') {
    if (
      !['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(String(value.status)) ||
      value.lastEventSeq !== value.seq ||
      (value.status === 'SUCCEEDED' && (typeof value.versionId !== 'string' || !value.versionId))
    )
      throw new Error('Invalid terminal event')
    return { type, seq: value.seq, lastEventSeq: value.seq }
  }
  return { type, seq: value.seq }
}

// Native EventSource reconnects with Last-Event-ID; never resubmit the paid run.
// https://html.spec.whatwg.org/multipage/server-sent-events.html
export function subscribeToRun(runId: string, after: number, handlers: RunEventHandlers) {
  handlers.state('connecting')
  const source = new EventSource(`/api/runs/${encodeURIComponent(runId)}/events?after=${after}`)
  let cursor = after
  let failures = 0
  let openedAt: number | undefined
  let closed = false
  const close = () => {
    if (closed) return
    closed = true
    source.close()
  }
  const fail = (message: string) => {
    if (closed) return
    close()
    handlers.state('paused')
    handlers.error(message)
  }
  source.addEventListener('open', () => {
    if (closed) return
    openedAt = performance.now()
    handlers.state('connected')
  })
  source.addEventListener('error', () => {
    if (closed) return
    // A 200 response immediately followed by EOF is still a failed connection.
    // Idle streams surviving a heartbeat interval may rotate normally.
    if (openedAt !== undefined && performance.now() - openedAt >= 15000) failures = 0
    openedAt = undefined
    if (++failures >= 3 || source.readyState === 2)
      fail('实时进度连接已暂停，请刷新生成状态；后台任务不会因此取消。')
    else handlers.state('reconnecting')
  })
  source.addEventListener('stream.reset', () => fail('任务事件暂不可用，请刷新生成状态。'))
  source.addEventListener('message', () => fail('任务事件格式异常，请刷新生成状态。'))
  const types: RunEvent['type'][] = [
    'run.queued',
    'stage.started',
    'plan.ready',
    'artifact.created',
    'run.completed',
  ]
  for (const type of types)
    source.addEventListener(type, (message) => {
      if (closed) return
      let event: RunEvent
      try {
        event = decode(runId, type, message as MessageEvent<string>)
        if (event.seq <= cursor) return
        if (event.seq !== cursor + 1) throw new Error('Event gap')
      } catch {
        fail('任务事件不完整或格式异常，请刷新生成状态。')
        return
      }
      cursor = event.seq
      failures = 0
      if (event.type === 'run.completed') {
        close()
        handlers.state('idle')
      }
      handlers.event(event)
    })
  return close
}
