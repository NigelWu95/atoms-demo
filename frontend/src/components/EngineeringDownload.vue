<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import type { VersionSummary } from '../api/artifacts'
import { downloadEngineering, engineeringFilename } from '../api/engineering'
import { errorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'

const props = defineProps<{
  projectId: string | null
  version: VersionSummary | null
  loading: boolean
  compact?: boolean
}>()
const session = useSessionStore()
const descriptionId = useId()
const busy = ref(false)
const error = ref('')
const notice = ref('')
const unavailable = computed(() => {
  if (!session.ready) return '请先连接访客会话。'
  if (props.loading) return '正在加载版本，请稍候。'
  if (!props.version || props.version.projectId !== props.projectId)
    return '尚无可下载的已保存版本。'
  return ''
})
let pending: AbortController | null = null
let objectUrl: string | null = null
let revokeTimer: ReturnType<typeof setTimeout> | undefined
function releaseUrl() {
  clearTimeout(revokeTimer)
  revokeTimer = undefined
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = null
}
function reset() {
  pending?.abort()
  pending = null
  busy.value = false
  error.value = ''
  notice.value = ''
  releaseUrl()
}
watch(() => [props.projectId, props.version?.id], reset, { flush: 'sync' })
onBeforeUnmount(reset)

async function download() {
  if (busy.value || unavailable.value || !props.version) return
  const target = { ...props.version }
  const request = new AbortController()
  pending = request
  busy.value = true
  error.value = ''
  notice.value = ''
  releaseUrl()
  try {
    const filename = engineeringFilename(target)
    const blob = await downloadEngineering(target, request.signal)
    if (pending !== request || request.signal.aborted || !session.ready) return
    objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = filename
    document.body.append(link)
    try {
      link.click()
    } finally {
      link.remove()
    }
    // Do not revoke before the browser has consumed the URL; also release on target change/unmount.
    // https://developer.mozilla.org/en-US/docs/Web/API/URL/revokeObjectURL_static
    revokeTimer = setTimeout(releaseUrl, 30000)
    notice.value = `V${target.versionNo} 工程已交给浏览器下载，请按包内 README 配置、构建和启动。`
  } catch (failure) {
    if (pending !== request || request.signal.aborted) return
    releaseUrl()
    error.value = errorMessage(failure)
  } finally {
    if (pending === request) {
      pending = null
      busy.value = false
    }
  }
}
</script>

<template>
  <section class="engineering-download" :class="{ compact }" aria-label="版本源码导出">
    <div class="download-actions">
      <button
        class="button secondary"
        type="button"
        :disabled="busy || !!unavailable"
        :aria-busy="busy"
        :aria-describedby="descriptionId"
        @click="download"
      >
        {{ busy ? '正在准备源码…' : error ? '重试导出版本源码 ZIP' : '导出版本源码 ZIP' }}
      </button>
      <span v-if="version && !unavailable && !compact"
        >选中 V{{ version.versionNo }} · Vue + Spring Boot 17 + PostgreSQL</span
      >
    </div>
    <p :id="descriptionId" class="muted" :class="{ 'visually-hidden': compact }">
      使用当前模板重新组装所选版本，不保证与正在预览的构建相同。如需预览同款，请使用「下载预览工程
      ZIP」。
      包含源码与初始数据，不包含平台账号、对话、线上业务数据和密钥。需自行配置独立数据库并构建启动。
    </p>
    <p v-if="unavailable && !compact" class="muted">{{ unavailable }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.engineering-download.compact {
  padding: 0;
  border: 0;
}
.engineering-download {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
}
.download-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 1rem;
}
.engineering-download p {
  margin: 0.5rem 0 0;
  overflow-wrap: anywhere;
}
.download-actions span,
.engineering-download p {
  font-size: 0.8125rem;
  line-height: 1.6;
}
</style>
