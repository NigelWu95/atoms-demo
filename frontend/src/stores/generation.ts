import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { CreateRun } from '../api/runs'
import { randomId } from '../runtime/randomId'

// In-memory pending intents survive route changes, not browser reloads. No credentials are stored.
export const useGenerationStore = defineStore('generation', () => {
  const pending = ref<Record<string, { key: string; body: CreateRun; preserveDraft: boolean }>>({})
  const repairRuns = ref<Record<string, string>>({})
  function intent(projectId: string, body: CreateRun, preserveDraft = false) {
    return (
      pending.value[projectId] ??
      (pending.value[projectId] = { key: randomId(), body: { ...body }, preserveDraft })
    )
  }
  function resolved(projectId: string, runId?: string) {
    if (runId) {
      if (pending.value[projectId]?.preserveDraft) repairRuns.value[projectId] = runId
      else delete repairRuns.value[projectId]
    }
    delete pending.value[projectId]
  }
  function canRestoreDraft(projectId: string, runId: string) {
    return !pending.value[projectId]?.preserveDraft && repairRuns.value[projectId] !== runId
  }
  function reset() {
    pending.value = {}
    repairRuns.value = {}
  }
  return { pending, intent, resolved, canRestoreDraft, reset }
})
