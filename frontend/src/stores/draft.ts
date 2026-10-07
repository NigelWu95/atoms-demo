import { defineStore } from 'pinia'
import { ref } from 'vue'

// Unsubmitted drafts are memory-only; submitted messages live on the server. Never store credentials here.
export const useDraftStore = defineStore('draft', () => {
  const prompt = ref('')
  const projectDrafts = ref<Record<string, { prompt: string; intentKey: string }>>({})
  const creation = ref<{ prompt: string; key: string } | null>(null)
  function creationKey(value: string) {
    if (creation.value?.prompt !== value)
      creation.value = { prompt: value, key: crypto.randomUUID() }
    return creation.value.key
  }
  function attach(projectId: string, value: string, intentKey: string) {
    projectDrafts.value[projectId] = { prompt: value, intentKey }
    creation.value = null
  }
  function updateProjectPrompt(projectId: string, value: string) {
    const current = projectDrafts.value[projectId]
    if (current?.prompt !== value)
      projectDrafts.value[projectId] = { prompt: value, intentKey: crypto.randomUUID() }
  }
  return { prompt, projectDrafts, creationKey, attach, updateProjectPrompt }
})
