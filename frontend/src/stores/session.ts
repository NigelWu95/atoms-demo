import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, errorMessage } from '../api/client'
import { initializeSession, type Identity } from '../api/session'

export const useSessionStore = defineStore('session', () => {
  const identity = ref<Identity | null>(null)
  const state = ref<'connecting' | 'ready' | 'failed'>('connecting')
  const error = ref('')
  const ready = computed(() => state.value === 'ready')
  let pending: Promise<void> | null = null
  api.onSessionFailure((failure) => {
    state.value = 'failed'
    error.value = errorMessage(failure)
  })
  function initialize(): Promise<void> {
    if (pending) return pending
    state.value = 'connecting'
    error.value = ''
    pending = initializeSession()
      .then((value) => {
        identity.value = value
        state.value = 'ready'
      })
      .catch((failure: unknown) => {
        error.value = errorMessage(failure)
        state.value = 'failed'
      })
      .finally(() => {
        pending = null
      })
    return pending
  }
  return { identity, state, error, ready, initialize }
})
