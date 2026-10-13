<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { instancePath, instanceState, loadInstanceApp, type AppInstance } from '../api/instances'
import { errorMessage } from '../api/client'
import { mountFrame } from '../runtime/mountFrame'
const props = defineProps<{ instance: AppInstance }>()
const container = ref<HTMLElement | null>(null),
  message = ref('正在连接独立实例…'),
  failed = ref(false)
const controller = new AbortController()
let dispose: (() => void) | undefined
onMounted(async () => {
  try {
    await loadInstanceApp(props.instance, controller.signal)
    if (!container.value || controller.signal.aborted) return
    dispose = mountFrame(
      container.value,
      instancePath(props.instance.projectId, props.instance.id) + '/document',
      (request, key) => instanceState(props.instance, request, key, controller.signal),
      (status, text) => {
        message.value = text
        failed.value = status === 'error'
      },
      '独立实例应用预览',
    )
  } catch (error) {
    if (!controller.signal.aborted) {
      message.value = errorMessage(error)
      failed.value = true
    }
  }
})
onBeforeUnmount(() => {
  controller.abort()
  dispose?.()
})
</script>
<template>
  <div class="runtime-preview">
    <p
      class="runtime-feedback"
      :role="failed ? 'alert' : 'status'"
      :class="{ 'inline-error': failed }"
    >
      独立实例 · {{ message }}
    </p>
    <div ref="container" class="runtime-container" />
  </div>
</template>
