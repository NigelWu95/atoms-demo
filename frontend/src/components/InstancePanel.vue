<script setup lang="ts">
import { watch, useId } from 'vue'
import type { EngineeringBuild } from '../api/builds'
import type { AppInstance, InstanceStatus } from '../api/instances'
import { useAppInstance } from '../composables/useAppInstance'
import { useSessionStore } from '../stores/session'
const props = defineProps<{
  projectId: string | null
  versionId: string | null
  currentVersionId: string | null
  build: EngineeringBuild | null
}>()
const emit = defineEmits<{ running: [AppInstance | null] }>()
const session = useSessionStore(),
  descriptionId = useId()
const {
  instance,
  pending,
  busy,
  loading,
  error,
  canStart,
  canStop,
  start,
  stop,
  refresh,
  confirm,
} = useAppInstance(() =>
  session.ready && session.identity && props.projectId
    ? { ownerId: session.identity.userId, projectId: props.projectId }
    : null,
)
const labels: Record<InstanceStatus, string> = {
  QUEUED: '等待启动',
  STARTING: '启动中',
  RUNNING: '运行中',
  STOPPING: '停止中',
  STOPPED: '已停止',
  FAILED: '启动或运行失败',
  INTERRUPTED: '运行中断，需确认旧进程退出',
}
watch(
  () => [instance.value, props.versionId, props.currentVersionId] as const,
  () =>
    emit(
      'running',
      instance.value?.status === 'RUNNING' &&
        instance.value.versionId === props.versionId &&
        props.versionId === props.currentVersionId
        ? instance.value
        : null,
    ),
  { immediate: true },
)
</script>
<template>
  <section
    v-if="projectId && versionId"
    class="engineering-build"
    aria-label="本机应用进程"
    :aria-busy="busy || loading"
  >
    <div class="build-actions">
      <button
        class="button secondary"
        type="button"
        data-action="start-instance"
        :disabled="
          !canStart ||
          build?.status !== 'SUCCEEDED' ||
          build.versionId !== versionId ||
          currentVersionId !== versionId
        "
        :aria-describedby="descriptionId"
        @click="build && start(build.id)"
      >
        启动应用
      </button>
      <button
        v-if="instance && ['QUEUED', 'STARTING', 'RUNNING', 'STOPPING'].includes(instance.status)"
        class="button secondary"
        type="button"
        data-action="stop-instance"
        :disabled="!canStop"
        @click="stop"
      >
        {{ instance.status === 'STOPPING' ? '等待进程退出…' : '停止应用' }}
      </button>
      <button class="button secondary" type="button" :disabled="busy || loading" @click="refresh">
        刷新运行状态
      </button>
    </div>
    <p :id="descriptionId" class="muted">
      成功构建后启动真实 Spring Boot
      进程；独立数据库保留重启前的数据，与共享预览及已发布应用的数据分开。
    </p>
    <p v-if="currentVersionId !== versionId" class="muted">
      历史版本只读。如需运行该版本，请先恢复为当前版本并重新构建。
    </p>
    <p role="status">
      {{ instance ? labels[instance.status] : '尚未启动应用'
      }}<template v-if="instance"> · {{ instance.id.slice(0, 8) }}</template>
    </p>
    <p v-if="instance && instance.versionId !== versionId" class="muted">
      运行进程属于另一代码版本。请先停止旧进程，再启动当前版本。
    </p>
    <p v-if="instance?.errorCode" class="inline-error">
      {{ instance.errorCode }} · 请检查运行环境后重试；无法确认退出时不会启动重复进程。
    </p>
    <button
      v-if="pending"
      class="button secondary"
      type="button"
      :disabled="busy || loading"
      @click="confirm"
    >
      确认上次{{ pending.kind === 'start' ? '启动' : '停止' }}请求
    </button>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
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
.engineering-build button {
  min-height: 2.75rem;
}
.engineering-build p {
  margin: 0.5rem 0 0;
}
</style>
