import { computed, onBeforeUnmount, onMounted, readonly, ref } from 'vue'
import { checkHealth, HealthFailure } from './api/health'
import { ApiFailure, initializeApp } from './api/client'
import type { AppDescription } from './api/responses'

export function useStartup() {
  const phase = ref<'checking' | 'connecting' | 'connected' | 'failed'>('checking')
  const failure = ref<HealthFailure | ApiFailure | null>(null)
  const app = ref<AppDescription | null>(null)
  const requestId = computed(() =>
    failure.value instanceof ApiFailure ? failure.value.requestId : undefined,
  )
  let active: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  async function refresh() {
    if (active || disposed) return
    const controller = new AbortController()
    active = controller
    app.value = null
    phase.value = 'checking'
    // Keep the preceding failure visible while a user-initiated retry is pending.
    timer = setTimeout(() => controller.abort(new DOMException('Timed out', 'TimeoutError')), 15000)
    try {
      await checkHealth(controller.signal)
      if (disposed) return
      phase.value = 'connecting'
      const description = await initializeApp(controller.signal)
      if (disposed) return
      app.value = description
      failure.value = null
      phase.value = 'connected'
    } catch (error) {
      if (disposed) return
      failure.value =
        error instanceof HealthFailure || error instanceof ApiFailure
          ? error
          : new ApiFailure('NETWORK_ERROR')
      phase.value = 'failed'
    } finally {
      clearTimeout(timer)
      active = undefined
    }
  }

  onMounted(refresh)
  onBeforeUnmount(() => {
    disposed = true
    clearTimeout(timer)
    active?.abort()
  })
  return {
    phase: readonly(phase),
    failure: readonly(failure),
    app: readonly(app),
    requestId,
    refresh,
  }
}
