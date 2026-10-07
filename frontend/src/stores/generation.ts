import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { CreateRun } from '../api/runs'
import { randomId } from '../runtime/randomId'

// In-memory pending intents survive route changes, not browser reloads. No credentials are stored.
export const useGenerationStore = defineStore('generation', () => {
  const pending = ref<Record<string, { key: string; body: CreateRun }>>({})
  function intent(projectId: string, body: CreateRun) {
    return (
      pending.value[projectId] ??
      (pending.value[projectId] = { key: randomId(), body: { ...body } })
    )
  }
  function resolved(projectId: string) {
    delete pending.value[projectId]
  }
  return { pending, intent, resolved }
})
