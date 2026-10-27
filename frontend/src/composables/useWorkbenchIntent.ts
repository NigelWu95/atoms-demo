import { computed, ref, shallowRef } from 'vue'
import { ApiFailure, errorMessage } from '../api/client'
import { randomId } from '../runtime/randomId'

/** Keeps the exact request/key across uncertain writes. It never retries automatically. */
export function useWorkbenchIntent() {
  type Intent = { label: string; execute: () => Promise<void> }
  const intent = shallowRef<Intent | null>(null)
  const busy = ref(false)
  const error = ref('')
  let epoch = 0
  function clear() {
    epoch++
    intent.value = null
    busy.value = false
    error.value = ''
  }
  async function retry() {
    if (!intent.value || busy.value) return
    const request = epoch
    busy.value = true
    error.value = ''
    try {
      await intent.value.execute()
      if (request === epoch) intent.value = null
    } catch (failure) {
      if (request !== epoch) return
      error.value = errorMessage(failure)
      // These are definitive input/permission rejections; no write has been admitted.
      if (failure instanceof ApiFailure && [400, 401, 403, 404, 422].includes(failure.status))
        intent.value = null
    } finally {
      if (request === epoch) busy.value = false
    }
  }
  async function run(label: string, execute: (key: string) => Promise<void>) {
    if (intent.value || busy.value) return
    const key = randomId()
    intent.value = { label, execute: () => execute(key) }
    await retry()
  }
  return { busy, error, pending: computed(() => intent.value?.label ?? null), run, retry, clear }
}
