import { afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '../api/client'
import { useSessionStore } from './session'
import { useDraftStore } from './draft'
import { useGenerationStore } from './generation'
import { RECOVERY_KEY, writeRecovery } from '../runtime/generationRecovery'

afterEach(() => {
  api.resetScope()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  sessionStorage.clear()
  localStorage.clear()
})

it('binds recovery only after authenticated initialization and clears it on invalidation', async () => {
  setActivePinia(createPinia())
  const identity = {
    userId: '11111111-1111-4111-8111-111111111111',
    isGuest: true,
    displayName: '访客',
  }
  const projectId = '22222222-2222-4222-8222-222222222222'
  writeRecovery({ ownerId: identity.userId, isGuest: true, stamp: '' }, [
    {
      projectId,
      createdAt: Date.now(),
      repairRunId: null,
      expired: false,
      pending: {
        key: 'original',
        preserveDraft: false,
        body: {
          prompt: '私有需求',
          intent: 'CREATE',
          baseVersionId: null,
          expectedProjectRevision: 0,
        },
      },
    },
  ])
  const session = useSessionStore()
  const generation = useGenerationStore()
  const binding = vi.spyOn(generation, 'bind')
  let finish!: (value: Response) => void
  vi.stubGlobal(
    'fetch',
    vi.fn((path) =>
      path === '/api/session'
        ? new Promise<Response>((resolve) => {
            finish = resolve
          })
        : Promise.resolve(Response.json({ token: 'test', headerName: 'X-CSRF-TOKEN' })),
    ),
  )
  const connecting = session.initialize()
  await vi.waitFor(() => expect(finish).toBeDefined())
  expect(binding).not.toHaveBeenCalled()
  finish(Response.json(identity))
  await connecting
  expect(binding).toHaveBeenCalledWith(identity)
  expect(generation.pending).toEqual({})
  generation.activate(projectId, identity.userId)
  expect(generation.pending[projectId]?.body.prompt).toBe('私有需求')
  session.invalidate('已退出')
  expect(sessionStorage.getItem(RECOVERY_KEY)).toBeNull()
  expect(generation.pending).toEqual({})
  generation.$dispose()
})
it('does not remount private views when keyboard focus returns from the preview iframe', async () => {
  setActivePinia(createPinia())
  const store = useSessionStore()
  store.identity = { userId: 'guest', isGuest: true, displayName: '访客' }
  store.state = 'ready'
  const fetch = vi
    .fn()
    .mockImplementation((path) =>
      Promise.resolve(
        Response.json(
          path === '/api/me'
            ? { userId: 'account', isGuest: false, displayName: '账号' }
            : { token: 'test', headerName: 'X-CSRF-TOKEN' },
        ),
      ),
    )
  vi.stubGlobal('fetch', fetch)
  const stop = store.listen()
  try {
    window.dispatchEvent(new Event('focus'))
    expect(store.ready).toBe(true)
    expect(fetch).not.toHaveBeenCalled()
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.waitFor(() => expect(store.identity?.userId).toBe('account'))
    expect(store.ready).toBe(true)
  } finally {
    stop()
  }
})
it('clears private drafts and hides the old identity on session failure', async () => {
  setActivePinia(createPinia())
  const store = useSessionStore(),
    drafts = useDraftStore(),
    generation = useGenerationStore()
  store.identity = { userId: 'old', isGuest: false, displayName: '旧账号' }
  store.state = 'ready'
  drafts.prompt = '旧账号私有草稿'
  drafts.attach('old-project', '旧内容', 'old-intent')
  generation.pending = {
    'old-project': {
      key: 'key',
      preserveDraft: false,
      body: { prompt: 'secret', intent: 'CREATE', baseVersionId: null, expectedProjectRevision: 0 },
    },
  }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({}, { status: 401 })))
  await expect(api.request('/api/me', (value) => value)).rejects.toMatchObject({ status: 401 })
  expect(store.identity).toBeNull()
  expect(store.ready).toBe(false)
  expect(drafts.prompt).toBe('')
  expect(drafts.projectDrafts).toEqual({})
  expect(generation.pending).toEqual({})
})
it('does not restore a late identity after invalidation', async () => {
  setActivePinia(createPinia())
  const store = useSessionStore()
  let finish!: (response: Response) => void
  vi.stubGlobal(
    'fetch',
    vi.fn((path) =>
      path === '/api/session'
        ? new Promise<Response>((resolve) => {
            finish = resolve
          })
        : Promise.resolve(Response.json({ token: 'test', headerName: 'X-CSRF-TOKEN' })),
    ),
  )
  const initializing = store.initialize()
  await vi.waitFor(() => expect(finish).toBeDefined())
  store.invalidate('其他标签页已退出')
  finish(Response.json({ userId: 'old', isGuest: false, displayName: '旧账号' }))
  await initializing
  expect(store.identity).toBeNull()
  expect(store.state).toBe('failed')
})

it('reports a successful explicit login only after the identity transition completes', async () => {
  setActivePinia(createPinia())
  const store = useSessionStore()
  store.identity = { userId: 'guest', isGuest: true, displayName: '访客' }
  store.state = 'ready'
  useDraftStore().prompt = '旧输入'
  vi.stubGlobal(
    'fetch',
    vi.fn((path) =>
      Promise.resolve(
        Response.json(
          path === '/api/auth/email/verifications'
            ? { userId: 'account', isGuest: false, displayName: '账号' }
            : { token: 'test', headerName: 'X-CSRF-TOKEN' },
        ),
      ),
    ),
  )
  expect(await store.login('challenge', '123456')).toBe(true)
  expect(store.identity?.userId).toBe('account')
  expect(useDraftStore().prompt).toBe('')
})
it('does not report success for a second login while a transition is in flight', async () => {
  setActivePinia(createPinia())
  const store = useSessionStore()
  let finish!: (response: Response) => void
  vi.stubGlobal(
    'fetch',
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    ),
  )
  const pending = store.login('first', '123456').catch(() => undefined)
  expect(await store.login('second', '123456')).toBe(false)
  store.invalidate('已换账号')
  finish(Response.json({ token: 'test', headerName: 'X-CSRF-TOKEN' }))
  await pending
  expect(store.identity).toBeNull()
})
