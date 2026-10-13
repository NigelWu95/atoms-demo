<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import {
  buildArchiveFilename,
  downloadBuildArchive,
  type BuildArchiveTarget,
} from '../api/buildArchive'
import { errorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'
const props = defineProps<{ target: BuildArchiveTarget; label?: string }>()
const session = useSessionStore()
const busy = ref(false),
  error = ref(''),
  notice = ref('')
let pending: AbortController | null = null
let url: string | null = null
let timer: ReturnType<typeof setTimeout> | undefined
function release() {
  clearTimeout(timer)
  timer = undefined
  if (url) URL.revokeObjectURL(url)
  url = null
}
function reset() {
  pending?.abort()
  pending = null
  busy.value = false
  error.value = ''
  notice.value = ''
  release()
}
watch(
  [
    () => props.target.id,
    () => props.target.projectId,
    () => session.ready,
    () => session.identity?.userId,
  ],
  reset,
  { flush: 'sync' },
)
onBeforeUnmount(reset)
async function download() {
  if (busy.value || !session.ready) return
  const target = { ...props.target },
    request = new AbortController()
  pending = request
  busy.value = true
  error.value = ''
  notice.value = ''
  release()
  try {
    const blob = await downloadBuildArchive(target, request.signal)
    if (pending !== request || request.signal.aborted || !session.ready) return
    url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = buildArchiveFilename(target)
    document.body.append(link)
    try {
      link.click()
    } finally {
      link.remove()
    }
    timer = setTimeout(release, 30000)
    notice.value = `构建 ${target.id.slice(0, 8)} 的冻结工程已交给浏览器下载。`
  } catch (failure) {
    if (pending === request && !request.signal.aborted) error.value = errorMessage(failure)
  } finally {
    if (pending === request) {
      pending = null
      busy.value = false
    }
  }
}
</script>
<template>
  <div class="build-archive-download">
    <button
      class="button secondary"
      type="button"
      :disabled="busy || !session.ready"
      :aria-busy="busy"
      :aria-label="label ?? `下载构建 ${target.id.slice(0, 8)} 的工程 ZIP`"
      @click="download"
    >
      {{ busy ? '正在下载构建快照…' : (label ?? '下载本次构建工程 ZIP') }}
    </button>
    <button
      v-if="busy"
      class="button secondary"
      type="button"
      data-action="cancel-download"
      @click="reset"
    >
      取消下载请求
    </button>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.build-archive-download {
  margin-block: 0.5rem;
  overflow-wrap: anywhere;
}
.build-archive-download button {
  min-height: 2.75rem;
  margin-inline-end: 0.5rem;
}
.build-archive-download p {
  margin: 0.5rem 0 0;
}
</style>
