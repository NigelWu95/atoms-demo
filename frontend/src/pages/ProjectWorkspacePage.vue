<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { developmentMode } from '../api/developmentMode'
import { errorMessage } from '../api/client'
import WorkbenchPage from './WorkbenchPage.vue'
import EngineeringWorkbenchPage from './EngineeringWorkbenchPage.vue'

const route = useRoute(),
  session = useSessionStore()
const mode = ref<'ENGINEERING' | 'LEGACY' | null>(null),
  error = ref('')
let epoch = 0,
  controller = new AbortController()
async function load() {
  const current = ++epoch
  controller.abort()
  controller = new AbortController()
  mode.value = null
  error.value = ''
  if (!session.ready) return
  try {
    const result = await developmentMode(controller.signal)
    if (current === epoch) mode.value = result
  } catch (failure) {
    if (current === epoch) error.value = errorMessage(failure)
  }
}
watch(() => [route.params.id, session.ready, session.identity?.userId], load, { immediate: true })
onBeforeUnmount(() => {
  epoch++
  controller.abort()
})
</script>
<template>
  <EngineeringWorkbenchPage v-if="mode === 'ENGINEERING'" :key="String(route.params.id)" />
  <WorkbenchPage v-else-if="mode === 'LEGACY'" :key="String(route.params.id)" />
  <section v-else class="simple-page" aria-label="打开开发工作台">
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else role="status">正在打开开发工作台…</p>
    <button v-if="error" type="button" @click="load">重新加载</button>
  </section>
</template>
