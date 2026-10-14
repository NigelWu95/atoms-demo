<script setup lang="ts">
import { computed, useId, watch } from 'vue'
import type { VersionSummary } from '../api/artifacts'
import type { BuildStage, BuildStatus, EngineeringBuild } from '../api/builds'
import { useEngineeringBuild } from '../composables/useEngineeringBuild'
import { useSessionStore } from '../stores/session'
import BuildArchiveDownload from './BuildArchiveDownload.vue'

const props = defineProps<{
  projectId: string | null
  version: VersionSummary | null
  loading: boolean
  controller?: ReturnType<typeof useEngineeringBuild>
}>()
const session = useSessionStore()
const emit = defineEmits<{ selected: [EngineeringBuild | null] }>()
const descriptionId = useId()
const unavailable = computed(() => {
  if (!session.ready || !session.identity) return '请先连接访客会话。'
  if (props.loading) return '正在加载版本，请稍候。'
  if (!props.version || props.version.projectId !== props.projectId)
    return '尚无可构建的已保存版本。'
  return ''
})
const {
  build,
  history,
  events,
  pending,
  busy,
  loading,
  error,
  canSubmit,
  canCancel,
  submit,
  cancel,
  confirm,
  refresh,
} =
  props.controller ??
  useEngineeringBuild(() =>
    !unavailable.value && props.projectId && props.version && session.identity
      ? {
          ownerId: session.identity.userId,
          projectId: props.projectId,
          versionId: props.version.id,
        }
      : null,
  )
const statuses: Record<BuildStatus, string> = {
  QUEUED: '等待构建',
  BUILDING: '构建中',
  CANCELLING: '正在取消',
  SUCCEEDED: '构建完成',
  FAILED: '构建失败',
  CANCELLED: '构建已取消',
  INTERRUPTED: '构建中断',
}
watch(build, (value) => emit('selected', value), { immediate: true })
const stages: Record<BuildStage, string> = {
  QUEUED: '等待执行槽',
  PREPARING: '冻结工程快照',
  INSTALLING: '安装锁定依赖',
  CHECKING_FRONTEND: '检查前端类型与测试',
  BUILDING_FRONTEND: '构建前端',
  CHECKING_BACKEND: '检查后端并打包',
  VERIFYING: '核验工程与 JAR',
  COMPLETE: '检查完成',
}
function date(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="engineering-build" aria-label="本机构建工程" :aria-busy="busy || loading">
    <div class="build-actions">
      <button
        class="button secondary"
        type="button"
        data-action="build"
        :disabled="!canSubmit"
        :aria-describedby="descriptionId"
        @click="submit"
      >
        {{
          busy && pending?.kind === 'submit'
            ? '正在提交构建…'
            : `构建${version ? ` V${version.versionNo}` : ''} 工程`
        }}
      </button>
      <button
        v-if="build && ['QUEUED', 'BUILDING', 'CANCELLING'].includes(build.status)"
        class="button secondary"
        type="button"
        data-action="cancel-build"
        :disabled="!canCancel"
        @click="cancel"
      >
        {{ build.status === 'CANCELLING' ? '正在取消，等待进程退出' : '取消构建' }}
      </button>
      <button
        class="button secondary"
        type="button"
        :disabled="!!unavailable || busy || loading"
        @click="refresh"
      >
        刷新构建状态
      </button>
    </div>
    <p :id="descriptionId" class="muted">
      冻结选中版本，在本机检查并构建 Vue + Spring Boot 工程。原共享预览保持不变，不自动发布。
    </p>
    <p v-if="unavailable" class="muted">{{ unavailable }}</p>
    <p v-else-if="loading && !build" role="status">正在读取构建记录…</p>
    <p v-else-if="!build && !error" class="muted">该版本尚无构建记录。</p>
    <template v-if="build">
      <p role="status">
        <template v-if="build.status === 'SUCCEEDED'">构建完成，可启动本机应用。</template>
        <template v-else>{{ statuses[build.status] }} · {{ stages[build.stage] }}</template>
      </p>
      <p v-if="build.errorMessage" class="inline-error">
        {{ build.errorMessage }}（{{ build.errorCode }}）
      </p>
      <p class="muted">构建 {{ build.id.slice(0, 8) }} · {{ date(build.createdAt) }}</p>
      <BuildArchiveDownload
        v-if="build.archiveSha256"
        :key="build.id"
        :target="{ id: build.id, projectId: build.projectId }"
      />
    </template>
    <div v-if="pending && !busy" class="pending-build">
      <p role="status">上次操作结果尚未确认，请使用原操作标识确认，不会创建新的提交意图。</p>
      <button
        class="button secondary"
        type="button"
        data-action="confirm-build"
        :disabled="!!unavailable || loading"
        @click="confirm"
      >
        {{ pending.kind === 'submit' ? '确认上次构建请求' : '确认上次取消请求' }}
      </button>
    </div>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <details v-if="events.length">
      <summary>本次构建阶段（仅展示受控状态）</summary>
      <ol>
        <li v-for="event in events.slice(-10)" :key="event.seq">
          {{ stages[event.stage] }} · {{ statuses[event.status] }}
        </li>
      </ol>
    </details>
    <details v-if="history.length">
      <summary>该版本的近期构建记录（项目最近 20 项内）</summary>
      <ul>
        <li v-for="item in history" :key="item.id">
          {{ date(item.createdAt) }} · {{ statuses[item.status] }} · {{ item.id.slice(0, 8) }}
          <BuildArchiveDownload
            v-if="item.archiveSha256 && item.id !== build?.id"
            :target="{ id: item.id, projectId: item.projectId }"
          />
        </li>
      </ul>
    </details>
  </section>
</template>

<style scoped>
.engineering-build {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
  font-size: 0.8125rem;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.build-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
.build-actions button,
.pending-build button {
  min-height: 2.75rem;
}
.engineering-build p {
  margin: 0.5rem 0 0;
}
.engineering-build summary {
  cursor: pointer;
  padding-block: 0.5rem;
}
.engineering-build ol,
.engineering-build ul {
  margin-block: 0.5rem;
  padding-inline-start: 1.25rem;
}
</style>
