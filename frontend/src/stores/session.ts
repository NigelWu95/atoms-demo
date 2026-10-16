import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, ApiFailure, errorMessage } from '../api/client'
import { decodeIdentity, initializeSession, refreshCsrf, type Identity } from '../api/session'
import { verifyEmailCode } from '../api/emailAuth'
import { useDraftStore } from './draft'
import { useGenerationStore } from './generation'

export const useSessionStore = defineStore('session', () => {
  const identity = ref<Identity | null>(null)
  const state = ref<'connecting' | 'ready' | 'failed'>('connecting')
  const error = ref('')
  const ready = computed(() => state.value === 'ready')
  const revision = ref(0)
  const hasConnected = ref(false)
  let pending: Promise<void> | null = null
  let changing = false
  let channel: BroadcastChannel | null = null
  function clearPrivate() {
    api.resetScope()
    revision.value++
    identity.value = null
    useDraftStore().reset()
    useGenerationStore().reset()
  }
  function invalidate(message: string) {
    clearPrivate()
    pending = null
    state.value = 'failed'
    error.value = message
  }
  api.onSessionFailure((failure) => invalidate(errorMessage(failure)))
  function initialize(): Promise<void> {
    if (pending) return pending
    if (ready.value || changing) return Promise.resolve()
    const epoch = revision.value
    state.value = 'connecting'
    error.value = ''
    pending = initializeSession()
      .then((value) => {
        if (epoch !== revision.value) return
        identity.value = value
        useGenerationStore().bind(value)
        state.value = 'ready'
        hasConnected.value = true
      })
      .catch((failure: unknown) => {
        if (epoch !== revision.value) return
        error.value = errorMessage(failure)
        state.value = 'failed'
      })
      .finally(() => {
        if (epoch === revision.value) pending = null
      })
    return pending
  }
  function announce() {
    // This broadcast marker contains no identity, email, code, or cookie.
    try {
      localStorage.setItem('atoms.session.changed', `${Date.now()}:${Math.random()}`)
    } catch {
      /* storage may be disabled */
    }
    channel?.postMessage('changed')
  }
  async function transition(action: () => Promise<Identity | null>) {
    if (changing) return
    changing = true
    api.resetScope()
    pending = null
    state.value = 'connecting'
    error.value = ''
    let epoch = revision.value
    try {
      await refreshCsrf()
      const result = await action()
      if (epoch !== revision.value) return
      clearPrivate()
      epoch = revision.value
      const value = result ?? (await initializeSession())
      if (result) await refreshCsrf()
      if (epoch !== revision.value) return
      identity.value = value
      state.value = 'ready'
      hasConnected.value = true
      announce()
      useGenerationStore().bind(value)
    } catch (failure) {
      if (epoch === revision.value) {
        if (identity.value && failure instanceof ApiFailure && [422, 429].includes(failure.status))
          state.value = 'ready'
        else invalidate(errorMessage(failure))
      }
      throw failure
    } finally {
      changing = false
    }
  }
  function login(challengeId: string, code: string) {
    return transition(() => verifyEmailCode(challengeId, code))
  }
  function logout() {
    return transition(async () => {
      await api.request('/api/session', () => undefined, { method: 'DELETE' })
      return null
    })
  }
  async function reconcile() {
    if (!ready.value || changing || pending) return
    const previous = identity.value
    let epoch = revision.value
    // Hide private views while a restored tab revalidates its shared cookie.
    state.value = 'connecting'
    try {
      const value = await api.request('/api/me', decodeIdentity)
      if (epoch !== revision.value) return
      if (value.userId !== previous?.userId || value.isGuest !== previous?.isGuest) {
        clearPrivate()
        epoch = revision.value
      }
      await refreshCsrf()
      if (epoch !== revision.value) return
      identity.value = value
      useGenerationStore().bind(value)
      state.value = 'ready'
      error.value = ''
    } catch (failure) {
      if (epoch === revision.value) invalidate(errorMessage(failure))
    }
  }
  function listen() {
    const changed = () => {
      if (changing) return
      invalidate('其他标签页已更新账号，正在重新连接。')
      void initialize()
    }
    if (typeof BroadcastChannel !== 'undefined') {
      channel = new BroadcastChannel('atoms-session')
      channel.onmessage = (event) => {
        if (event.data === 'changed') changed()
      }
    }
    const storage = (event: StorageEvent) => {
      if (!channel && event.key === 'atoms.session.changed') changed()
    }
    const visible = () => {
      if (document.visibilityState === 'visible') void reconcile()
    }
    window.addEventListener('storage', storage)
    // Window focus also fires when leaving a sandbox iframe; that is not an identity transition.
    window.addEventListener('pageshow', visible)
    document.addEventListener('visibilitychange', visible)
    return () => {
      channel?.close()
      channel = null
      window.removeEventListener('storage', storage)
      window.removeEventListener('pageshow', visible)
      document.removeEventListener('visibilitychange', visible)
    }
  }
  return {
    identity,
    state,
    error,
    ready,
    revision,
    hasConnected,
    initialize,
    invalidate,
    login,
    logout,
    listen,
    reconcile,
  }
})
