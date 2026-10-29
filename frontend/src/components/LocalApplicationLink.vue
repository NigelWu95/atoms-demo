<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiFailure, errorMessage, protocolFailure } from '../api/client'
import { getLocalAddress, type LocalApplicationAddress } from '../api/projectDelivery'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'
import { useSessionStore } from '../stores/session'

const props = defineProps<{
  projectId: string
  ownerId: string
  run: ExecutionTask | null
}>()
const session = useSessionStore()
const address = ref<LocalApplicationAddress | null>(null),
  loading = ref(false),
  error = ref(''),
  copied = ref(false)
let epoch = 0,
  request = new AbortController()
let retry: ReturnType<typeof setTimeout> | undefined
const target = computed(() => {
  const run = props.run
  return session.ready &&
    session.identity?.userId === props.ownerId &&
    run?.status === 'READY' &&
    !run.stopRequested
    ? `${props.ownerId}:${props.projectId}:${run.id}:${run.buildId}:${run.sourceVersionId}`
    : null
})
async function load(attempt = 0) {
  clearTimeout(retry)
  retry = undefined
  const current = ++epoch
  request.abort()
  request = new AbortController()
  address.value = null
  error.value = ''
  copied.value = false
  loading.value = false
  const run = props.run
  if (!target.value || !run) return
  loading.value = true
  try {
    const result = await getLocalAddress(props.projectId, run.id, request.signal)
    if (current !== epoch) return
    if (
      result.runId !== run.id ||
      result.buildId !== run.buildId ||
      result.sourceVersionId !== run.sourceVersionId
    )
      return protocolFailure()
    address.value = result
  } catch (failure) {
    if (
      current === epoch &&
      failure instanceof ApiFailure &&
      failure.code === 'PUBLICATION_NOT_READY' &&
      attempt < 10
    ) {
      retry = setTimeout(() => {
        if (current === epoch) void load(attempt + 1)
      }, 1000)
      return
    }
    if (current === epoch) error.value = errorMessage(failure)
  } finally {
    if (current === epoch && retry === undefined) loading.value = false
  }
}
async function copy() {
  const value = address.value,
    current = epoch
  if (!value) return
  try {
    await navigator.clipboard.writeText(value.url)
    if (current === epoch) copied.value = true
  } catch {
    if (current === epoch) error.value = '复制失败，请从地址框手动复制。'
  }
}
watch(target, () => void load(), { immediate: true, flush: 'sync' })
const visible = () => {
  if (!document.hidden && target.value) void load()
}
document.addEventListener('visibilitychange', visible)
onBeforeUnmount(() => {
  clearTimeout(retry)
  epoch++
  request.abort()
  document.removeEventListener('visibilitychange', visible)
})
</script>

<template>
  <div class="engineering-actions local-application-link" aria-label="应用发布地址">
    <template v-if="address">
      <a class="button primary" :href="address.url" target="_blank" rel="noopener noreferrer">
        打开应用 ↗
      </a>
      <input aria-label="应用地址" :value="address.url" readonly />
      <button type="button" @click="copy">{{ copied ? '已复制' : '复制链接' }}</button>
      <small class="muted">独立进程 · 独立端口访问</small>
    </template>
    <p v-else-if="loading" role="status">正在核对应用地址…</p>
    <p v-else-if="!target" class="muted">启动并发布后，这里会显示应用访问链接。</p>
    <template v-if="error">
      <p role="alert" class="inline-error">{{ error }}</p>
      <button v-if="target" type="button" :disabled="loading" @click="load()">重新获取地址</button>
    </template>
  </div>
</template>

<style scoped>
.local-application-link {
  flex-wrap: wrap;
}
.local-application-link input {
  width: 15rem;
  max-width: 100%;
  min-width: 0;
}
.local-application-link p {
  margin: 0;
}
</style>
