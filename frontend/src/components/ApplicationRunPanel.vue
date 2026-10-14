<script setup lang="ts">
import { computed, useId } from 'vue'
import type { VersionSummary } from '../api/artifacts'
import type { EngineeringBuild as Build } from '../api/builds'
import type { AppInstance } from '../api/instances'
import { useSessionStore } from '../stores/session'
import { useEngineeringBuild } from '../composables/useEngineeringBuild'
import { useAppInstance } from '../composables/useAppInstance'
import { useRunApplication } from '../composables/useRunApplication'
import EngineeringBuild from './EngineeringBuild.vue'
import InstancePanel from './InstancePanel.vue'

const props = defineProps<{
  projectId: string | null
  ownerId: string | null
  currentVersionId: string | null
  version: VersionSummary | null
  loading: boolean
}>()
const emit = defineEmits<{ selected: [Build | null]; running: [AppInstance | null]; preview: [] }>()
const session = useSessionStore(),
  descriptionId = useId()
const target = computed(() =>
  session.ready &&
  session.identity &&
  session.identity.userId === props.ownerId &&
  props.projectId &&
  !props.loading &&
  props.version?.projectId === props.projectId
    ? { ownerId: session.identity.userId, projectId: props.projectId, versionId: props.version.id }
    : null,
)
const builds = useEngineeringBuild(() => target.value)
const runtime = useAppInstance(() => target.value)
const { build } = builds
const { canRun, canCancel, message, run, cancel } = useRunApplication(
  () => (props.version?.id === props.currentVersionId ? target.value : null),
  builds,
  runtime,
  () => emit('preview'),
)
</script>

<template>
  <section class="application-run" aria-label="运行应用流程">
    <div class="run-actions">
      <button
        type="button"
        class="button primary"
        data-action="run-application"
        :disabled="!canRun"
        :aria-describedby="descriptionId"
        @click="run"
      >
        运行应用
      </button>
      <button
        v-if="canCancel"
        type="button"
        class="button secondary"
        data-action="cancel-auto-run"
        @click="cancel"
      >
        取消自动启动
      </button>
    </div>
    <p :id="descriptionId" role="status">{{ message }}</p>
    <p v-if="version && version.id !== currentVersionId" class="muted">
      历史版本请先恢复为当前版本，再运行应用。
    </p>
    <p v-else-if="!version" class="muted">生成并保存首个版本后可运行应用。</p>
  </section>
  <EngineeringBuild
    :project-id="projectId"
    :version="version"
    :loading="loading"
    :controller="builds"
    @selected="emit('selected', $event)"
  />
  <InstancePanel
    :project-id="projectId"
    :version-id="version?.id ?? null"
    :current-version-id="currentVersionId"
    :build="build"
    :controller="runtime"
    @running="emit('running', $event)"
  />
</template>

<style scoped>
.application-run {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
  font-size: 0.8125rem;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.run-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.run-actions button {
  min-height: 2.75rem;
}
.application-run p {
  margin: 0.5rem 0 0;
}
</style>
