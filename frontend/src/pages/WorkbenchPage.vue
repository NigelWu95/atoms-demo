<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import PromptComposer from '../components/PromptComposer.vue'
import { useDraftStore } from '../stores/draft'
import { useSessionStore } from '../stores/session'
import { getProject, type Project } from '../api/projects'
import { errorMessage } from '../api/client'
import ProjectHeading from '../components/ProjectHeading.vue'
import ArtifactPanel from '../components/ArtifactPanel.vue'
import ConversationPanel from '../components/ConversationPanel.vue'
import PublicationPanel from '../components/PublicationPanel.vue'
import { useProjectDraft } from '../composables/useProjectDraft'
import { matchesRepair, type RepairProposal } from '../runtime/previewRepair'

const draft = useDraftStore()
const session = useSessionStore()
const route = useRoute()
const projectId = computed(() => (typeof route.params.id === 'string' ? route.params.id : null))
const project = ref<Project | null>(null)
const loading = ref(false)
const error = ref('')
let requestRevision = 0
const prompt = computed({
  get: () =>
    projectId.value ? (draft.projectDrafts[projectId.value]?.prompt ?? '') : draft.prompt,
  set: (value: string) => {
    if (projectId.value) draft.updateProjectPrompt(projectId.value, value)
    else draft.prompt = value
  },
})
const autosave = useProjectDraft(
  () =>
    session.ready && project.value?.ownerId === session.identity?.userId ? project.value : null,
  prompt,
  () => !!(projectId.value && draft.projectDrafts[projectId.value]),
)
function canLeave() {
  return (
    !autosave.dirty.value || window.confirm('草稿尚未确认保存，离开可能丢失当前输入。仍要离开吗？')
  )
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
async function reloadDraft() {
  if (window.confirm('重新读取会替换当前输入。请先复制需要保留的内容，确定继续吗？'))
    await autosave.reload()
}
const draftStatus = computed(
  () =>
    ({
      idle: '',
      loading: '正在读取草稿…',
      saved: '草稿已保存',
      dirty: '草稿待保存…',
      saving: '正在保存草稿…',
      error: '草稿尚未确认保存',
      conflict: '草稿存在冲突',
    })[autosave.status.value],
)
async function loadProject() {
  const request = ++requestRevision
  project.value = null
  error.value = ''
  loading.value = false
  if (!projectId.value || !session.ready) return
  loading.value = true
  try {
    const result = await getProject(projectId.value)
    if (request === requestRevision) project.value = result
  } catch (failure) {
    if (request === requestRevision) error.value = errorMessage(failure)
  } finally {
    if (request === requestRevision) loading.value = false
  }
}
watch(
  () => [projectId.value, session.ready, session.identity?.userId],
  () => {
    void loadProject()
  },
  { immediate: true },
)
const panel = ref<'conversation' | 'preview'>('conversation')
const repair = ref<RepairProposal | null>(null)
function prepareRepair(proposal: RepairProposal | null) {
  if (!proposal) {
    repair.value = null
    return
  }
  if (
    !project.value ||
    !session.ready ||
    !matchesRepair(proposal, project.value, session.identity?.userId ?? null)
  )
    return
  repair.value = proposal
  panel.value = 'conversation'
}
watch(
  [
    () => project.value?.id,
    () => project.value?.currentVersionId,
    () => project.value?.projectRevision,
    () => session.ready,
    () => session.identity?.userId,
  ],
  () => {
    repair.value = null
  },
  { flush: 'sync' },
)
</script>

<template>
  <section v-if="projectId && !project" class="simple-page" :aria-busy="loading">
    <h1 tabindex="-1">{{ error ? '项目无法打开' : '正在打开项目' }}</h1>
    <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <p v-else role="status" class="muted">
      {{ session.ready ? '正在读取项目资料…' : '请先连接访客会话。' }}
    </p>
    <div class="rename-actions">
      <button
        v-if="error"
        class="button secondary"
        type="button"
        :disabled="!session.ready"
        @click="loadProject"
      >
        重试打开
      </button>
      <RouterLink to="/projects" class="button secondary">返回我的项目</RouterLink>
    </div>
  </section>
  <div v-else class="workbench-page">
    <header class="workbench-header">
      <div>
        <RouterLink to="/" class="subtle-link">← 返回创作</RouterLink>
        <ProjectHeading v-if="project" :project="project" @renamed="project = $event" />
        <h1 v-else tabindex="-1">需求草稿 <span class="outline-badge">未保存</span></h1>
      </div>
      <PublicationPanel v-if="project?.currentVersionId" :key="project.id" :project="project" />
      <button v-else class="button primary" type="button" disabled title="应用生成后才能发布">
        发布应用 ↗
      </button>
    </header>
    <p class="workbench-notice">
      {{ project ? '项目名称已保存到数据库。' : '这是尚未保存的需求草稿。' }}
      项目内未提交需求会自动保存草稿；首页尚未创建项目的输入仅在当前页面保留。发布仅开放应用，不开放创作工作台。
    </p>
    <div v-if="project" class="draft-status">
      <span role="status" aria-live="polite">{{ draftStatus }}</span>
      <p v-if="autosave.error.value" role="alert" class="inline-error">
        {{ autosave.error.value }}
      </p>
      <button
        v-if="autosave.status.value === 'error'"
        type="button"
        class="button secondary"
        @click="autosave.retry"
      >
        重试草稿保存
      </button>
      <button
        v-if="autosave.status.value === 'conflict'"
        type="button"
        class="button secondary"
        @click="reloadDraft"
      >
        重新读取服务器草稿
      </button>
    </div>
    <div class="mobile-view-controls" role="group" aria-label="工作台区域">
      <button
        type="button"
        :aria-pressed="panel === 'conversation'"
        @click="panel = 'conversation'"
      >
        对话</button
      ><button type="button" :aria-pressed="panel === 'preview'" @click="panel = 'preview'">
        预览
      </button>
    </div>
    <div class="workbench-grid" :class="{ 'mobile-preview': panel === 'preview' }">
      <ConversationPanel
        v-if="project"
        :key="project.id"
        v-model="prompt"
        :project="project"
        :repair="repair"
        @dismiss-repair="repair = null"
        @changed="project = $event"
      />
      <section v-else class="conversation-panel" aria-labelledby="conversation-title">
        <div class="panel-heading">
          <h2 id="conversation-title">需求与对话</h2>
          <span class="muted">尚未开始生成</span>
        </div>
        <div class="conversation-body">
          <div class="agent-avatar" aria-hidden="true">✳</div>
          <h3>先把想法说清楚</h3>
          <p>你希望谁来使用这个应用？它需要完成什么？可以继续完善下方的需求。</p>
          <div class="workflow-explainer">
            <span>01 理解需求</span><span>02 构建应用</span><span>03 校验与预览</span>
          </div>
          <p class="muted">请先返回首页创建项目，再提交生成任务。</p>
        </div>
        <PromptComposer
          v-model="prompt"
          disabled
          label="继续完善你的需求"
          action-label="生成应用"
          hint="请先创建项目，再开始生成"
        />
      </section>
      <ArtifactPanel :project="project" @changed="project = $event" @repair="prepareRepair" />
    </div>
  </div>
</template>

<style scoped>
.draft-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 1rem;
  padding: 0.75rem 1.5rem;
  border-bottom: 1px solid var(--border);
  color: var(--muted);
  font-size: 0.8125rem;
}
.draft-status .inline-error {
  flex-basis: 100%;
}
@media (max-width: 768px) {
  .draft-status {
    padding-inline: 1rem;
  }
}
</style>
