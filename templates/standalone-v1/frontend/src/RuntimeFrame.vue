<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiFailure } from './api/client'
import { mountFrame } from './runtime/mountFrame'
import type { BridgeStatus } from './runtime/bridge'

const props = defineProps<{ title: string }>()
const container = ref<HTMLElement | null>(null)
const status = ref<BridgeStatus>('connecting')
const message = ref('正在加载应用文档…')
let dispose: (() => void) | undefined

onMounted(() => {
  if (!container.value) return
  dispose = mountFrame(
    container.value,
    async () => {
      throw new ApiFailure('READ_ONLY_PREVIEW')
    },
    (next, detail) => {
      status.value = next
      message.value = detail
    },
    props.title,
  )
})
onBeforeUnmount(() => dispose?.())
</script>

<template>
  <section class="runtime-panel" aria-labelledby="runtime-title">
    <div class="runtime-heading">
      <h2 id="runtime-title">应用界面</h2>
      <span class="instance-label">隔离沙箱</span>
    </div>
    <p v-if="status === 'error' || status === 'unsaved'" class="error-message" role="alert">
      {{ message }}
    </p>
    <p v-else class="detail" role="status" aria-live="polite" aria-atomic="true">{{ message }}</p>
    <p v-if="status === 'error'" class="detail">
      可使用上方“重新加载应用”重试，未保存的输入会丢失。
    </p>
    <div ref="container" class="runtime-container"></div>
    <p class="detail">数据服务尚未接入，本页交互不代表数据已保存。重新加载会清除未保存的输入。</p>
  </section>
</template>
