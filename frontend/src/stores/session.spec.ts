import { afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '../api/client'
import { useSessionStore } from './session'
import { useDraftStore } from './draft'
import { useGenerationStore } from './generation'

afterEach(() => {
  api.resetScope()
  vi.unstubAllGlobals()
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
