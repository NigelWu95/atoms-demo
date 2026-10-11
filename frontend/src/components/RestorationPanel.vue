<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { restoreVersion, type RestoreInput, type VersionSummary } from '../api/artifacts'
import { getProject, type Project } from '../api/projects'
import { ApiFailure, errorMessage } from '../api/client'
import { randomId } from '../runtime/randomId'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ project: Project; version: VersionSummary | null }>()
const emit = defineEmits<{ changed: [Project]; locked: [boolean] }>()
const session = useSessionStore()
const open = ref(false)
const confirmed = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const pending = ref<{ projectId: string; body: RestoreInput; key: string } | null>(null)
const history = computed(() => props.version && props.version.id !== props.project.currentVersionId)
let alive = true
function showConfirmation() {
  open.value = true
  confirmed.value = false
}
watch(
  () => [props.version?.id, props.project.projectRevision],
  () => {
    confirmed.value = false
    if (!pending.value) open.value = false
  },
)
watch(
  () => busy.value || !!pending.value,
  (locked) => emit('locked', locked),
)
onBeforeUnmount(() => {
  alive = false
})
async function retry() {
  if (!session.ready || busy.value || !pending.value) return
  const intent = pending.value
  busy.value = true
  error.value = ''
  let committed = false
  try {
    const restored = await restoreVersion(intent.projectId, intent.body, intent.key)
    committed = true
    // A replay may describe an older restore: fetch the current pointer instead of rebuilding it.
    const project = await getProject(intent.projectId)
    if (!alive) return
    pending.value = null
    open.value = false
    notice.value = `已恢复为 V${restored.versionNo}，当前项目状态已同步。`
    emit('changed', project)
  } catch (failure) {
    if (!alive) return
    if (
      !committed &&
      failure instanceof ApiFailure &&
      failure.status >= 400 &&
      failure.status < 500
    )
      pending.value = null
    error.value = errorMessage(failure)
    confirmed.value = false
  } finally {
    if (alive) busy.value = false
  }
}
async function submit() {
  if (
    !session.ready ||
    busy.value ||
    pending.value ||
    !confirmed.value ||
    !history.value ||
    !props.version
  )
    return
  notice.value = ''
  pending.value = {
    projectId: props.project.id,
    key: randomId(),
    body: {
      sourceVersionId: props.version.id,
      expectedProjectRevision: props.project.projectRevision,
    },
  }
  await retry()
}
async function refresh() {
  if (!session.ready || busy.value || pending.value) return
  busy.value = true
  error.value = ''
  try {
    const project = await getProject(props.project.id)
    if (alive) emit('changed', project)
  } catch (failure) {
    if (alive) error.value = errorMessage(failure)
  } finally {
    if (alive) busy.value = false
  }
}
</script>

<template>
  <section
    v-if="history || pending || notice || error"
    class="restoration-panel"
    aria-label="代码版本恢复"
    :aria-busy="busy"
  >
    <button
      v-if="history && !open && !pending"
      class="button secondary"
      type="button"
      data-action="restore"
      :disabled="busy || !session.ready"
      @click="showConfirmation"
    >
      恢复此版本
    </button>
    <div v-if="open && !pending">
      <p>
        将 V{{ version?.versionNo }}
        的代码恢复为一个新版本。保留所有历史，不回滚业务数据，不自动发布。旧代码可能不兼容现有数据，请先确认。
      </p>
      <label class="publication-consent"
        ><input
          v-model="confirmed"
          type="checkbox"
          :disabled="busy || !session.ready"
        />我确认只恢复代码</label
      >
      <div class="publication-actions">
        <button
          class="button primary"
          type="button"
          data-action="confirm-restore"
          :disabled="!confirmed || busy || !session.ready || !history"
          @click="submit"
        >
          确认恢复为新版本
        </button>
        <button class="button secondary" type="button" :disabled="busy" @click="open = false">
          取消恢复
        </button>
      </div>
    </div>
    <div v-if="pending">
      <p>恢复结果尚未完成确认。重试会使用原版本和请求标识，不会重复创建版本。</p>
      <button
        class="button secondary"
        type="button"
        data-action="retry-restore"
        :disabled="busy || !session.ready"
        @click="retry"
      >
        {{ busy ? '正在恢复…' : '重试原恢复操作' }}
      </button>
    </div>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <button
      v-if="error && !pending"
      class="button secondary"
      type="button"
      :disabled="busy || !session.ready"
      @click="refresh"
    >
      刷新项目状态
    </button>
  </section>
</template>

<style scoped>
.restoration-panel {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
}
</style>
