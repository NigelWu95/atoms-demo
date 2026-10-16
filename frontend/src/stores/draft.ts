import { defineStore } from 'pinia'
import { ref } from 'vue'
import { randomId } from '../runtime/randomId'

// In-memory edit buffer; workbench project drafts are synchronized by useProjectDraft. Never store credentials here.
export const useDraftStore = defineStore('draft', () => {
  const prompt = ref('')
  const projectDrafts = ref<Record<string, { prompt: string; intentKey: string }>>({})
  const creation = ref<{ prompt: string; key: string } | null>(null)
  function creationKey(value: string) {
    if (creation.value?.prompt !== value) creation.value = { prompt: value, key: randomId() }
    return creation.value.key
  }
  function attach(projectId: string, value: string, intentKey: string) {
    projectDrafts.value[projectId] = { prompt: value, intentKey }
    creation.value = null
  }
  function updateProjectPrompt(projectId: string, value: string) {
    const current = projectDrafts.value[projectId]
    if (current?.prompt !== value)
      projectDrafts.value[projectId] = { prompt: value, intentKey: randomId() }
  }
  function reset() {
    prompt.value = ''
    projectDrafts.value = {}
    creation.value = null
  }
  return { prompt, projectDrafts, creationKey, attach, updateProjectPrompt, reset }
})
