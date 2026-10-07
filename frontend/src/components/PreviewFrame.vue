<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import type { Version } from '../api/artifacts'
import type { BridgeStatus } from '../runtime/bridge'
import { mountFrame } from '../runtime/mountFrame'
import { createPreviewContext, type PreviewMode } from '../runtime/previewContext'
import { validateScript } from '../runtime/validateScript'
import type { Observation } from '../api/previewReports'
import PreviewChecks from './PreviewChecks.vue'

// The parent keys this component by project/version/mode; a reload replaces the iframe entirely.
const props = defineProps<{ version: Version; mode: PreviewMode }>()
const container = ref<HTMLDivElement | null>(null)
const status = ref<BridgeStatus>('connecting')
const message = ref('正在加载预览…')
const observation = ref<Observation>({ status: 'UNKNOWN', checks: [], errors: [] })
let cleanup: (() => void) | undefined
let revision = 0
function reload() {
  const current = ++revision
  cleanup?.()
  status.value = 'connecting'
  message.value = '正在加载预览…'
  observation.value = { status: 'UNKNOWN', checks: [], errors: [] }
  const report = (next: BridgeStatus, text: string) => {
    if (revision !== current) return
    status.value = next
    message.value = text
    if (next === 'error') {
      observation.value.status = 'FAILED'
      const bounded = text.slice(0, 1000)
      if (observation.value.errors.length < 5 && !observation.value.errors.includes(bounded))
        observation.value.errors.push(bounded)
    } else if (next === 'ready' && observation.value.status !== 'FAILED') {
      observation.value = {
        status: 'PASSED',
        checks: ['SCRIPT_PARSE', 'SDK_STARTUP', 'RUNTIME_ERRORS'],
        errors: [],
      }
    }
  }
  const invalid = validateScript(props.version.artifact.files['main.js'])
  if (invalid) {
    report('error', invalid)
    return
  }
  observation.value.checks = ['SCRIPT_PARSE']
  if (!container.value) return
  cleanup = mountFrame(
    container.value,
    `/api/projects/${encodeURIComponent(props.version.projectId)}/versions/${encodeURIComponent(props.version.id)}/document?previewMode=${props.mode}`,
    createPreviewContext(
      props.version.projectId,
      props.version.id,
      props.mode,
      props.version.artifact.manifest.initialState,
    ),
    report,
  )
}
onMounted(reload)
onBeforeUnmount(() => {
  revision++
  cleanup?.()
})
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
    <PreviewChecks
      :project-id="version.projectId"
      :version-id="version.id"
      :observation="observation"
      :read-only="mode === 'HISTORY'"
    />
  </div>
</template>
