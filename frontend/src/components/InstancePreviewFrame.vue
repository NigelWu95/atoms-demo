<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { instancePath, instanceState, loadInstanceApp, type AppInstance } from '../api/instances'
import { errorMessage } from '../api/client'
import { mountFrame } from '../runtime/mountFrame'
const props = defineProps<{ instance: AppInstance }>()
const emit = defineEmits<{ availability: [executionId: string, ready: boolean] }>()
const execution = props.instance
const container = ref<HTMLElement | null>(null),
  message = ref('正在连接本机应用…'),
  failed = ref(false)
const controller = new AbortController()
let dispose: (() => void) | undefined
onMounted(async () => {
  try {
    await loadInstanceApp(execution, controller.signal)
    if (!container.value || controller.signal.aborted) return
    dispose = mountFrame(
      container.value,
      instancePath(execution.projectId, execution.id) + '/document',
      (request, key) => instanceState(execution, request, key, controller.signal),
      (status, text) => {
        if (controller.signal.aborted) return
        message.value = text
        failed.value = status === 'error'
        if (status === 'ready') emit('availability', execution.id, true)
        else if (status === 'error' || status === 'connecting')
          emit('availability', execution.id, false)
      },
      '本机应用预览',
    )
  } catch (error) {
    if (!controller.signal.aborted) {
      message.value = errorMessage(error)
      failed.value = true
      emit('availability', execution.id, false)
    }
  }
})
onBeforeUnmount(() => {
  controller.abort()
  emit('availability', execution.id, false)
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
      本机应用 · {{ message }}
    </p>
    <div ref="container" class="runtime-container" />
  </div>
</template>
