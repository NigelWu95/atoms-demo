<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Project } from '../api/projects'
import { getVersion, listVersions, type Version, type VersionSummary } from '../api/artifacts'
import { errorMessage } from '../api/client'
import PreviewFrame from './PreviewFrame.vue'
import SourceViewer from './SourceViewer.vue'
import VersionReview from './VersionReview.vue'
import RestorationPanel from './RestorationPanel.vue'
import EngineeringDownload from './EngineeringDownload.vue'
import ApplicationRunPanel from './ApplicationRunPanel.vue'
import type { AppInstance } from '../api/instances'
import InstancePreviewFrame from './InstancePreviewFrame.vue'
import BuildArchiveDownload from './BuildArchiveDownload.vue'
import ToolDisclosure from './ToolDisclosure.vue'
import { useSessionStore } from '../stores/session'
import type { PreviewDiagnosis, RepairProposal } from '../runtime/previewRepair'

const props = defineProps<{ project: Project | null }>()
const emit = defineEmits<{ changed: [Project]; repair: [RepairProposal | null] }>()
const restorationLocked = ref(false)
const view = ref<'preview' | 'code'>('preview')
const device = ref<'desktop' | 'mobile'>('desktop')
const versions = ref<VersionSummary[]>([])
const cursor = ref<string | null>(null)
const selected = ref('')
const version = ref<Version | null>(null)
const loading = ref(false)
const error = ref('')
const previewTools = ref<HTMLElement | null>(null)
const running = ref<AppInstance | null>(null)
const previewKind = ref<'shared' | 'instance'>('shared')
const session = useSessionStore()
const readyExecutionId = ref<string | null>(null)
const canRepair = computed(
  () =>
    session.ready &&
    session.identity?.userId === props.project?.ownerId &&
    !!version.value &&
    version.value.id === props.project?.currentVersionId &&
    previewKind.value === 'shared' &&
    view.value === 'preview' &&
    !loading.value,
)
function prepareRepair(diagnosis: PreviewDiagnosis) {
  if (!canRepair.value || !props.project || !version.value) return
  emit('repair', {
    ownerId: props.project.ownerId,
    projectId: props.project.id,
    baseVersionId: version.value.id,
    expectedProjectRevision: props.project.projectRevision,
    versionNo: version.value.versionNo,
    diagnosis,
  })
}
watch(
  [() => props.project?.id, () => selected.value, () => previewKind.value],
  () => emit('repair', null),
  { flush: 'sync' },
)
// Both the frame and its download use this one validated execution, never the selected build.
const previewRuntime = computed(() =>
  previewKind.value === 'instance' &&
  session.ready &&
  session.identity?.userId === props.project?.ownerId &&
  running.value?.status === 'RUNNING' &&
  running.value.projectId === props.project?.id &&
  running.value.versionId === version.value?.id &&
  version.value?.id === props.project?.currentVersionId
    ? running.value
    : null,
)
const previewDownload = computed(() =>
  previewRuntime.value && readyExecutionId.value === previewRuntime.value.id
    ? { id: previewRuntime.value.buildId, projectId: previewRuntime.value.projectId }
    : null,
)
watch(
  () => previewRuntime.value?.id,
  () => {
    readyExecutionId.value = null
  },
  { flush: 'sync' },
)
function setAvailability(executionId: string, ready: boolean) {
  if (executionId === previewRuntime.value?.id) readyExecutionId.value = ready ? executionId : null
}
function setRunning(value: AppInstance | null) {
  if (value && value.id !== running.value?.id) previewKind.value = 'instance'
  running.value = value
}
function showRunningPreview() {
  view.value = 'preview'
  previewKind.value = 'instance'
}
watch([() => props.project?.id, () => selected.value], () => {
  running.value = null
  previewKind.value = 'shared'
})
const mode = computed(() =>
  selected.value === props.project?.currentVersionId ? 'CURRENT' : 'HISTORY',
)
let revision = 0
async function loadVersions(append = false) {
  if (!props.project?.currentVersionId) return
  const request = revision
  try {
    const page = await listVersions(props.project.id, append ? cursor.value : null)
    if (request !== revision) return
    versions.value = append ? [...versions.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (failure) {
    if (request === revision) error.value = errorMessage(failure)
  }
}
watch(
  [() => props.project?.id, () => props.project?.currentVersionId],
  () => {
    revision++
    versions.value = []
    cursor.value = null
    error.value = ''
    selected.value = props.project?.currentVersionId ?? ''
    void loadVersions()
  },
  { immediate: true },
)
// Vue cleanup invalidates pending loads on selection change and on unmount.
// https://vuejs.org/guide/essentials/watchers.html#side-effect-cleanup
watch(
  [() => props.project?.id, () => selected.value],
  async (_, __, onCleanup) => {
    let active = true
    onCleanup(() => {
      active = false
    })
    version.value = null
    error.value = ''
    loading.value = false
    if (!props.project || !selected.value) return
    loading.value = true
    try {
      const result = await getVersion(props.project.id, selected.value)
      if (active) version.value = result
    } catch (failure) {
      if (active) error.value = errorMessage(failure)
    } finally {
      if (active) loading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <section class="preview-panel" aria-label="应用预览与工具" tabindex="0">
    <div class="artifact-toolbar">
      <div class="preview-toolbar">
        <div role="group" aria-label="产物视图">
          <button type="button" :aria-pressed="view === 'preview'" @click="view = 'preview'">
            预览
          </button>
          <button type="button" :aria-pressed="view === 'code'" @click="view = 'code'">代码</button>
        </div>
        <div role="group" aria-label="预览尺寸">
          <button type="button" :aria-pressed="device === 'desktop'" @click="device = 'desktop'">
            桌面
          </button>
          <button type="button" :aria-pressed="device === 'mobile'" @click="device = 'mobile'">
            手机
          </button>
        </div>
      </div>
      <div v-if="project?.currentVersionId" class="version-toolbar">
        <label
          ><span class="version-label">代码版本</span>
          <select v-model="selected" aria-label="代码版本" :disabled="restorationLocked">
            <option v-for="item in versions" :key="item.id" :value="item.id">
              V{{ item.versionNo }} ·
              {{ item.id === project.currentVersionId ? '当前版本' : '历史只读' }}
            </option>
          </select></label
        >
        <button v-if="cursor" class="button secondary" type="button" @click="loadVersions(true)">
          更多版本
        </button>
      </div>
    </div>
    <ApplicationRunPanel
      :project-id="project?.id ?? null"
      :owner-id="project?.ownerId ?? null"
      :current-version-id="project?.currentVersionId ?? null"
      :version="version"
      :loading="loading"
      @running="setRunning"
      @preview="showRunningPreview"
    >
      <template #preview-controls>
        <div v-if="version" class="preview-environments" role="group" aria-label="预览运行环境">
          <button
            type="button"
            aria-label="平台共享预览"
            :aria-pressed="previewKind === 'shared'"
            @click="previewKind = 'shared'"
          >
            共享预览
          </button>
          <button
            type="button"
            aria-label="本机运行预览"
            :aria-pressed="previewKind === 'instance'"
            @click="previewKind = 'instance'"
          >
            本机预览
          </button>
        </div>
      </template>
      <div class="artifact-actions">
        <section class="preview-download" aria-label="预览工程下载">
          <BuildArchiveDownload
            v-if="previewDownload"
            :target="previewDownload"
            label="下载预览工程 ZIP"
          />
          <button
            v-else
            type="button"
            class="button secondary"
            disabled
            title="先构建并启动，等待本机运行预览就绪"
          >
            下载预览工程 ZIP
          </button>
        </section>
        <EngineeringDownload
          compact
          :project-id="project?.id ?? null"
          :version="version"
          :loading="loading"
        />
        <div class="artifact-tool-links">
          <ToolDisclosure label="预览检查">
            <div ref="previewTools" />
            <p v-if="previewKind !== 'shared' || !version" class="muted">
              共享预览开启后可查看检查报告。
            </p>
          </ToolDisclosure>
          <ToolDisclosure label="应用说明与修改记录" class="artifact-description">
            <template v-if="version">
              <h2 class="artifact-title">{{ version.artifact.manifest.title }}</h2>
              <p class="artifact-summary">{{ version.artifact.manifest.summary }}</p>
              <p class="muted">
                V{{ version.versionNo }} · 数据与源码分别保存 · 受限浏览器运行，非安全认证
              </p>
              <RouterLink
                :to="`/preview/${version.projectId}/${version.id}`"
                target="_blank"
                rel="noopener noreferrer"
                class="subtle-link"
                >共享预览新窗口 ↗</RouterLink
              >
              <VersionReview
                :key="version.projectId"
                :project-id="version.projectId"
                :version-id="version.id"
              />
            </template>
            <p v-else>生成并保存版本后查看应用说明。</p>
          </ToolDisclosure>
          <ToolDisclosure label="下载说明">
            <p v-if="previewDownload">
              当前预览：V{{ version?.versionNo }} · 构建
              {{ previewDownload.id.slice(0, 8) }}。下载其冻结的完整工程源码。
            </p>
            <p v-else>
              下载预览工程需先构建并启动，等待本机运行预览就绪；共享预览不作为工程一致性验收。
            </p>
            <p>
              导出版本源码 ZIP 使用当前模板重新组装所选版本，包含 Vue + Spring Boot 17 + PostgreSQL
              源码，不保证与正在预览的构建相同。
            </p>
            <p>
              两种下载只带初始数据，不包含平台账号、对话、线上业务数据和密钥。按包内说明配置独立数据库并构建启动。
            </p>
          </ToolDisclosure>
        </div>
      </div>
      <RestorationPanel
        v-if="project?.currentVersionId"
        :key="project.id"
        :project="project"
        :version="version"
        @changed="emit('changed', $event)"
        @locked="restorationLocked = $event"
      />
      <p v-if="error" class="inline-error" role="alert">{{ error }} 请重新打开项目。</p>
      <div class="preview-stage" :aria-busy="loading">
        <div class="preview-canvas" :class="{ 'mobile-canvas': device === 'mobile' }">
          <template v-if="version">
            <PreviewFrame
              v-if="previewKind === 'shared'"
              v-show="view === 'preview'"
              :key="version.id + mode"
              :version="version"
              :mode="mode"
              :repairable="canRepair"
              :tools-target="previewTools"
              @repair="prepareRepair"
            />
            <InstancePreviewFrame
              v-else-if="previewRuntime"
              v-show="view === 'preview'"
              :key="previewRuntime.id"
              :instance="previewRuntime"
              @availability="setAvailability"
            />
            <p v-else-if="view === 'preview'" role="status">
              当前版本没有运行中的本机应用。请构建并启动，或手动切换至平台共享预览。
            </p>
            <SourceViewer v-if="view === 'code'" :artifact="version.artifact" />
          </template>
          <div v-else class="preview-empty">
            <span class="preview-icon" aria-hidden="true">{{
              view === 'preview' ? '◫' : '⌘'
            }}</span>
            <h2 id="preview-title">
              {{
                loading
                  ? '正在加载版本…'
                  : view === 'preview'
                    ? '还没有生成应用'
                    : '还没有可查看的源码'
              }}
            </h2>
            <p>
              {{
                view === 'preview'
                  ? '生成完成后，你可以在这里直接使用应用。'
                  : '真实生成的 HTML、CSS 和 JavaScript 将显示在这里。'
              }}
            </p>
            <span v-if="!loading" class="outline-badge">等待首个代码版本</span>
          </div>
        </div>
      </div>
    </ApplicationRunPanel>
  </section>
</template>

<style scoped>
.artifact-actions {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 8px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--border);
}
.artifact-tool-links {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.preview-download :deep(.build-archive-download) {
  margin: 0;
}
.preview-download :deep(button) {
  margin: 0;
}
@media (max-width: 767px) {
  .artifact-actions {
    gap: 0 8px;
  }
  .artifact-actions :deep(.button) {
    font-size: 11px;
    padding: 8px;
  }
}
</style>
