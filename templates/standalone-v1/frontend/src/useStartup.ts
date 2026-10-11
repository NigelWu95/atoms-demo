import { onBeforeUnmount, onMounted, readonly, ref } from 'vue'
import { checkHealth, HealthFailure } from './api/health'

export function useStartup() {
  const phase = ref<'checking' | 'connected' | 'failed'>('checking')
  const failure = ref<HealthFailure | null>(null)
  let active: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  async function refresh() {
    if (active || disposed) return
    const controller = new AbortController()
    active = controller
    phase.value = 'checking'
    // Keep the preceding failure visible while a user-initiated retry is pending.
    timer = setTimeout(() => controller.abort(new DOMException('Timed out', 'TimeoutError')), 5000)
    try {
      await checkHealth(controller.signal)
      if (disposed) return
      failure.value = null
      phase.value = 'connected'
    } catch (error) {
      if (disposed) return
      failure.value = error instanceof HealthFailure ? error : new HealthFailure('offline')
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
  return { phase: readonly(phase), failure: readonly(failure), refresh }
}
