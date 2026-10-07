import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { afterEach, expect, it, vi } from 'vitest'

const script = readFileSync('../backend/src/main/resources/runtime/bootstrap.js', 'utf8')
const channelId = 'e1de3855-413f-4884-8e77-16e622511233'
type DocumentState = { value: Record<string, unknown>; revision: number; stateSchemaVersion: 1 }
type Sdk = {
  storage: {
    get(): Promise<DocumentState>
    save(value: object, revision: number): Promise<DocumentState>
  }
}
function setup() {
  const listeners = new Map<string, (event: unknown) => void>()
  const parent = { postMessage: vi.fn() }
  const win = {
    parent,
    location: { href: `http://localhost/api/document#atoms-channel=${channelId}` },
    addEventListener: (type: string, callback: (event: unknown) => void) =>
      listeners.set(type, callback),
    removeEventListener: (type: string) => listeners.delete(type),
    Atoms: { ready: vi.fn<(cb: (sdk: Sdk) => Promise<void>) => void>() },
  }
  const sandbox = {
    window: win,
    URL,
    URLSearchParams,
    // Real public HTTP exposes getRandomValues, but not randomUUID.
    crypto: { getRandomValues: crypto.getRandomValues.bind(crypto) },
    setTimeout,
    clearTimeout,
    startAtomsRuntime: vi.fn<(launch: () => void) => void>(),
  }
  runInNewContext(script, sandbox)
  const launch = vi.fn()
  sandbox.startAtomsRuntime(launch)
  const port = {
    onmessage: null as ((event: { data: unknown }) => void) | null,
    postMessage: vi.fn(),
    close: vi.fn(),
    start: vi.fn(),
  }
  function connect() {
    const message = (type: string) => ({
      source: parent,
      origin: 'http://localhost',
      data: { protocol: 'atoms-runtime-v1', type, channelId },
      ports: [port],
    })
    listeners.get('message')?.(message('INIT'))
    listeners.get('message')?.(message('CONNECT'))
  }
  const respond = (index: number, body: object) => {
    const sent = port.postMessage.mock.calls[index]?.[0]
    port.onmessage?.({
      data: {
        protocol: 'atoms-runtime-v1',
        type: 'RESULT',
        channelId,
        requestId: sent.requestId,
        ...body,
      },
    })
  }
  return { win, listeners, parent, launch, port, connect, respond }
}
afterEach(() => vi.useRealTimers())
it('does not create an unresolved save when the RPC queue is already full', async () => {
  const s = setup()
  s.connect()
  let sdk: Sdk | undefined
  s.win.Atoms.ready(async (value) => {
    sdk = value
  })
  await vi.waitFor(() => expect(sdk).toBeDefined())
  s.port.postMessage.mockClear()
  const reads = Array.from({ length: 16 }, () => sdk!.storage.get().catch(() => null))
  await expect(sdk!.storage.save({ count: 1 }, 0)).rejects.toMatchObject({ code: 'RPC_BUSY' })
  s.respond(0, { ok: true, result: { value: {}, revision: 0, stateSchemaVersion: 1 } })
  const save = sdk!.storage.save({ count: 2 }, 0)
  s.respond(16, { ok: true, result: { value: { count: 2 }, revision: 1, stateSchemaVersion: 1 } })
  await expect(save).resolves.toMatchObject({ revision: 1 })
  s.listeners.get('pagehide')?.({})
  await Promise.all(reads)
})
it('gates artifact execution on a parent handshake and validates the origin', () => {
  const s = setup()
  expect(s.launch).not.toHaveBeenCalled()
  s.listeners.get('message')?.({
    source: s.parent,
    origin: 'https://wrong.invalid',
    data: { protocol: 'atoms-runtime-v1', type: 'INIT', channelId },
  })
  expect(s.parent.postMessage).not.toHaveBeenCalled()
  s.connect()
  expect(s.launch).toHaveBeenCalledOnce()
  expect(s.parent.postMessage).toHaveBeenCalledWith(
    { protocol: 'atoms-runtime-v1', type: 'HELLO', channelId },
    'http://localhost',
  )
  s.listeners.get('pagehide')?.({})
})
it('retains the save intent after unknown failure and get; exact retry reuses requestId', async () => {
  const s = setup()
  s.connect()
  let sdk: Sdk | undefined
  s.win.Atoms.ready(async (value) => {
    sdk = value
  })
  await vi.waitFor(() => expect(sdk).toBeDefined())
  s.port.postMessage.mockClear()
  const first = sdk!.storage.save({ count: 1 }, 0)
  s.respond(0, { ok: false, error: { code: 'INVALID_RESPONSE', message: 'unknown' } })
  await expect(first).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  const get = sdk!.storage.get()
  s.respond(1, { ok: true, result: { value: { count: 0 }, revision: 0, stateSchemaVersion: 1 } })
  await get
  await expect(sdk!.storage.save({ count: 2 }, 0)).rejects.toMatchObject({
    code: 'SAVE_UNRESOLVED',
  })
  const retry = sdk!.storage.save({ count: 1 }, 0)
  expect(s.port.postMessage.mock.calls[2]?.[0].requestId).toBe(
    s.port.postMessage.mock.calls[0]?.[0].requestId,
  )
  s.respond(2, { ok: true, result: { value: { count: 1 }, revision: 1, stateSchemaVersion: 1 } })
  expect((await retry).revision).toBe(1)
  s.listeners.get('pagehide')?.({})
})
it('times out RPC and rejects pending calls when disposed; ignores old replies', async () => {
  vi.useFakeTimers()
  const s = setup()
  s.connect()
  let sdk: Sdk | undefined
  s.win.Atoms.ready(async (value) => {
    sdk = value
  })
  await vi.advanceTimersByTimeAsync(0)
  const read = sdk!.storage.get()
  const timeout = expect(read).rejects.toMatchObject({ code: 'RPC_TIMEOUT' })
  await vi.advanceTimersByTimeAsync(15000)
  await timeout
  const second = sdk!.storage.get()
  const disposed = expect(second).rejects.toMatchObject({ code: 'RUNTIME_DISPOSED' })
  s.listeners.get('pagehide')?.({})
  await disposed
  expect(s.port.close).toHaveBeenCalled()
})
