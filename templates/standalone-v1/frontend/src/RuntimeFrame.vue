<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { createStateHandler } from './api/state'
import { mountFrame } from './runtime/mountFrame'
import type { BridgeStatus } from './runtime/bridge'

const props = defineProps<{ title: string; loadedRuntimeId: string }>()
const container = ref<HTMLElement | null>(null)
const status = ref<BridgeStatus>('connecting')
const message = ref('正在加载应用文档…')
let dispose: (() => void) | undefined
const lifetime = new AbortController()

onMounted(() => {
  if (!container.value) return
  dispose = mountFrame(
    container.value,
    createStateHandler(props.loadedRuntimeId, lifetime.signal),
    (next, detail) => {
      status.value = next
      message.value = detail
    },
    props.title,
  )
})
onBeforeUnmount(() => {
  lifetime.abort()
  dispose?.()
})
</script>

<template>
  <section class="runtime-panel" aria-labelledby="runtime-title">
    <div class="runtime-heading">
      <h2 id="runtime-title">应用界面</h2>
      <span class="instance-label">隔离沙箱</span>
    </div>
    <p
      v-if="['error', 'unsaved', 'unknown', 'conflict', 'offline'].includes(status)"
      class="error-message"
      role="alert"
    >
      {{ message }}
    </p>
    <p v-else class="detail" role="status" aria-live="polite" aria-atomic="true">{{ message }}</p>
    <p v-if="status === 'error'" class="detail">
      可使用上方“重新加载应用”重试，未保存的输入会丢失。
    </p>
    <div ref="container" class="runtime-container"></div>
    <p class="detail">数据仅属于当前访客，保存在此应用自己的数据库中。</p>
    <p v-if="status === 'unknown'" class="detail">
      重新加载会丢失待确认操作的重试信息，但不会撤销服务器可能已经完成的保存。
    </p>
  </section>
</template>
