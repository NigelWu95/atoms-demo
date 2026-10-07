<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiFailure, errorMessage } from '../api/client'
import {
  getPreviewReport,
  savePreviewReport,
  type Observation,
  type PreviewReport,
} from '../api/previewReports'

const props = defineProps<{
  projectId: string
  versionId: string
  observation: Observation
  readOnly: boolean
}>()
const saved = ref<Observation | null>(null)
const error = ref('')
const busy = ref(false)
const pending = ref<{ body: PreviewReport; key: string } | null>(null)
let active = true
let revision = 0
const label = (status: Observation['status']) =>
  ({ UNKNOWN: '尚未完成', PASSED: '检查通过', FAILED: '检查失败' })[status]
const fingerprint = (value: Observation) =>
  JSON.stringify([value.status, value.checks, value.errors])
const dirty = computed(
  () => saved.value && fingerprint(saved.value) !== fingerprint(props.observation),
)
async function load() {
  const request = ++revision
  error.value = ''
  try {
    const result = await getPreviewReport(props.projectId, props.versionId)
    if (active && request === revision) saved.value = result
  } catch (failure) {
    if (active && request === revision) error.value = errorMessage(failure)
  }
}
async function save() {
  if (busy.value || props.readOnly || (!pending.value && props.observation.status === 'UNKNOWN'))
    return
  if (!pending.value) {
    if (props.observation.status === 'UNKNOWN') return
    pending.value = {
      key: crypto.randomUUID(),
      body: {
        status: props.observation.status,
        checks: [...props.observation.checks],
        errors: [...props.observation.errors],
        viewport: {
          width: Math.min(10000, Math.max(1, innerWidth)),
          height: Math.min(10000, Math.max(1, innerHeight)),
        },
      },
    }
  }
  const intent = pending.value
  revision++
  busy.value = true
  error.value = ''
  try {
    await savePreviewReport(props.projectId, props.versionId, intent.body, intent.key)
    if (!active) return
    saved.value = intent.body
    pending.value = null
  } catch (failure) {
    if (!active) return
    if (failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500)
      pending.value = null
    error.value = errorMessage(failure)
  } finally {
    if (active) busy.value = false
  }
}
onMounted(() => void load())
onBeforeUnmount(() => {
  active = false
  revision++
})
</script>

<template>
  <section class="preview-checks" aria-label="版本预览检查">
    <p role="status" aria-live="polite">本次观察：{{ label(observation.status) }}</p>
    <ul v-if="observation.errors.length" class="inline-error">
      <li v-for="(message, index) in observation.errors" :key="index">{{ message }}</li>
    </ul>
    <p class="muted">仅检查脚本语法、SDK 启动和已观察到的运行错误，不代表全部功能或安全验收。</p>
    <p v-if="saved" class="muted">
      已保存报告：{{ label(saved.status) }}<span v-if="dirty"> · 当前结果尚未保存</span>
    </p>
    <p v-if="pending && !busy" class="muted">
      上次保存结果未确认，重试会使用原报告；新结果需确认后另存。
    </p>
    <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <button
      data-test="save-preview-report"
      type="button"
      class="button secondary"
      :disabled="busy || readOnly || (!pending && observation.status === 'UNKNOWN')"
      @click="save"
    >
      {{ busy ? '正在保存检查报告…' : pending ? '重试原检查报告' : '保存检查结果' }}
    </button>
    <button
      v-if="error && !pending"
      type="button"
      class="button secondary"
      :disabled="busy"
      @click="load"
    >
      刷新检查报告
    </button>
    <p v-if="readOnly" class="muted">历史版本仅查看报告，请在当前版本执行检查。</p>
  </section>
</template>
