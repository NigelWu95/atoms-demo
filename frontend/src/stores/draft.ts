import { defineStore } from 'pinia'
import { ref } from 'vue'

// Memory-only until the project/session persistence slice; not an auth store.
export const useDraftStore = defineStore('draft', () => {
  const prompt = ref('')
  return { prompt }
})
