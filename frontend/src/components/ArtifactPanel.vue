<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Project } from '../api/projects'
import { getVersion, listVersions, type Version, type VersionSummary } from '../api/artifacts'
import { errorMessage } from '../api/client'
import PreviewFrame from './PreviewFrame.vue'
import SourceViewer from './SourceViewer.vue'
import RestorationPanel from './RestorationPanel.vue'
import EngineeringDownload from './EngineeringDownload.vue'

const props = defineProps<{ project: Project | null }>()
const emit = defineEmits<{ changed: [Project] }>()
const restorationLocked = ref(false)
const view = ref<'preview' | 'code'>('preview')
const device = ref<'desktop' | 'mobile'>('desktop')
const versions = ref<VersionSummary[]>([])
const cursor = ref<string | null>(null)
const selected = ref('')
const version = ref<Version | null>(null)
const loading = ref(false)
const error = ref('')
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
  () => [props.project?.id, props.project?.currentVersionId],
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
  () => [props.project?.id, selected.value],
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
  <section class="preview-panel" aria-labelledby="preview-title">
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
        >代码版本
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
      <RouterLink
        v-if="version"
        :to="`/preview/${project.id}/${version.id}`"
        target="_blank"
        rel="noopener noreferrer"
        >独立预览 ↗</RouterLink
      >
    </div>
    <EngineeringDownload :project-id="project?.id ?? null" :version="version" :loading="loading" />
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
        <div class="browser-decoration" aria-hidden="true"><i /><i /><i /></div>
        <template v-if="version">
          <h2 id="preview-title" class="artifact-title">{{ version.artifact.manifest.title }}</h2>
          <p class="artifact-summary">{{ version.artifact.manifest.summary }}</p>
          <PreviewFrame
            v-show="view === 'preview'"
            :key="version.id + mode"
            :version="version"
            :mode="mode"
          />
          <SourceViewer v-if="view === 'code'" :artifact="version.artifact" />
        </template>
        <div v-else class="preview-empty">
          <span class="preview-icon" aria-hidden="true">{{ view === 'preview' ? '◫' : '⌘' }}</span>
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
    <footer class="preview-status">
      <span>{{ version ? `V${version.versionNo} · 数据与源码分别保存` : '暂无产物' }}</span
      ><span>{{ version ? '受限浏览器运行 · 非安全认证' : '未执行预览检查' }}</span>
    </footer>
  </section>
</template>
