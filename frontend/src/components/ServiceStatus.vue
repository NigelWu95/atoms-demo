<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { checkHealth } from '../api/health'

const state = ref<'loading' | 'connected' | 'failed'>('loading')
async function refresh() {
  state.value = 'loading'
  try {
    await checkHealth()
    state.value = 'connected'
  } catch {
    state.value = 'failed'
  }
}
onMounted(refresh)
</script>

<template>
  <div class="service-status" :data-state="state">
    <div role="status" aria-live="polite" class="status-line">
      <span class="status-dot" aria-hidden="true" />
      <span v-if="state === 'loading'">正在检查服务…</span>
      <span v-else-if="state === 'connected'">服务已连接</span>
      <span v-else>服务连接失败</span>
    </div>
    <p v-if="state === 'connected'" class="muted">本地 API · PostgreSQL</p>
    <button v-if="state === 'failed'" class="text-button" type="button" @click="refresh">
      重试连接
    </button>
  </div>
</template>
