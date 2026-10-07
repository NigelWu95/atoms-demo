<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import type { Version } from '../api/artifacts'
import { createRuntimeBridge, type BridgeStatus } from '../runtime/bridge'
import { createPreviewContext, type PreviewMode } from '../runtime/previewContext'

// The parent keys this component by project/version/mode; a reload replaces the iframe entirely.
const props = defineProps<{ version: Version; mode: PreviewMode }>()
const container = ref<HTMLDivElement | null>(null)
const status = ref<BridgeStatus>('connecting')
const message = ref('正在加载预览…')
let cleanup: (() => void) | undefined
function reload() {
  cleanup?.()
  const channelId = crypto.randomUUID()
  const frame = document.createElement('iframe')
  frame.title = '应用交互预览'
  frame.setAttribute('sandbox', 'allow-scripts')
  frame.referrerPolicy = 'no-referrer'
  const bridge = createRuntimeBridge(
    frame,
    channelId,
    createPreviewContext(
      props.version.projectId,
      props.version.id,
      props.mode,
      props.version.artifact.manifest.initialState,
    ),
    (next, text) => {
      status.value = next
      message.value = text
    },
  )
  let loaded = false
  frame.addEventListener('load', () => {
    if (loaded) {
      bridge.dispose()
      status.value = 'error'
      message.value = '预览发生导航，旧连接已关闭。请手动重新加载。'
      return
    }
    loaded = true
    bridge.start()
  })
  frame.src = `/api/projects/${encodeURIComponent(props.version.projectId)}/versions/${encodeURIComponent(props.version.id)}/document?previewMode=${props.mode}#atoms-channel=${channelId}`
  container.value?.replaceChildren(frame)
  cleanup = () => {
    bridge.dispose()
    frame.remove()
  }
}
onMounted(reload)
onBeforeUnmount(() => cleanup?.())
</script>

<template>
  <div class="runtime-preview">
    <div class="runtime-controls">
      <span v-if="mode === 'HISTORY'" class="outline-badge">历史只读 · 使用初始数据</span>
      <span v-else class="muted">私有预览 · 仅当前访客</span>
      <button
        class="button secondary"
        type="button"
        :disabled="status === 'saving'"
        @click="reload"
      >
        重新加载
      </button>
    </div>
    <p v-if="status === 'unsaved'" class="inline-error">
      请先在应用内解决保存失败。重新加载可能丢失输入，未确认的请求也可能已写入。
    </p>
    <div ref="container" class="runtime-container" />
    <p
      class="runtime-feedback"
      :class="{ 'inline-error': status === 'error' || status === 'unsaved' }"
      role="status"
      aria-live="polite"
    >
      {{ message }}
    </p>
  </div>
</template>
