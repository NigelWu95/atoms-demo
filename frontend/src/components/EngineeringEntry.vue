<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { capabilities } from '../api/projectWorkbench'
import { useSessionStore } from '../stores/session'
const props = defineProps<{ projectId: string }>()
const session = useSessionStore(),
  available = ref(false)
let epoch = 0,
  controller = new AbortController()
watch(
  () => [props.projectId, session.ready, session.identity?.userId],
  async () => {
    const request = ++epoch
    controller.abort()
    controller = new AbortController()
    available.value = false
    if (!session.ready) return
    try {
      const result = await capabilities(props.projectId, controller.signal)
      if (request === epoch) available.value = result.enabled
    } catch {
      /* Optional entry: the legacy workbench remains usable when the new module is unavailable. */
    }
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  epoch++
  controller.abort()
})
</script>
<template>
  <RouterLink v-if="available" :to="`/projects/${projectId}/engineering`" class="button secondary"
    >完整工程工作台 ↗</RouterLink
  >
</template>
