<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { useEngineeringWorkbench } from '../composables/useEngineeringWorkbench'
import { useProjectDraft } from '../composables/useProjectDraft'
import { useDraftStore } from '../stores/draft'
import { useSessionStore } from '../stores/session'
import { statusLabel, terminal } from '../api/projectWorkbenchTasks'
import EngineeringSourcePanel from '../components/EngineeringSourcePanel.vue'
import EngineeringRunPanel from '../components/EngineeringRunPanel.vue'
import LocalApplicationLink from '../components/LocalApplicationLink.vue'
import {
  buildRepairPrompt,
  matchesBuildRepair,
  type BuildRepairProposal,
} from '../runtime/buildRepair'

const route = useRoute(),
  session = useSessionStore(),
  drafts = useDraftStore()
const projectId = computed(() => String(route.params.id ?? ''))
const wb = reactive(useEngineeringWorkbench(() => projectId.value))
const prompt = computed({
  get: () => drafts.projectDrafts[projectId.value]?.prompt ?? '',
  set: (value: string) => drafts.updateProjectPrompt(projectId.value, value),
})
const autosave = reactive(
  useProjectDraft(
    () => (session.ready && wb.project?.ownerId === session.identity?.userId ? wb.project : null),
    prompt,
    () => !!drafts.projectDrafts[projectId.value],
  ),
)
const area = ref<'conversation' | 'engineering'>('engineering'),
  tab = ref<'preview' | 'source' | 'diff'>('preview')
const toolsOpen = ref(false)
const messages = ref<HTMLElement | null>(null)
watch(
  () => wb.agents[0]?.id,
  async (_id, previous) => {
    const panel = messages.value
    if (!panel || (previous && panel.scrollHeight - panel.scrollTop - panel.clientHeight > 80))
      return
    await nextTick()
    panel.scrollTop = panel.scrollHeight
  },
)
watch(
  () => wb.eventTarget?.id,
  async (id) => {
    if (!id) return
    area.value = 'conversation'
    await nextTick()
    if (messages.value) messages.value.scrollTop = messages.value.scrollHeight
  },
)
const draftLabel = computed(
  () =>
    ({
      idle: '',
      loading: '读取草稿…',
      saved: '草稿已保存',
      dirty: '草稿待保存',
      saving: '保存草稿…',
      error: '草稿未确认保存',
      conflict: '草稿存在冲突',
    })[autosave.status],
)
function canLeave() {
  return (
    !(autosave.dirty || wb.intent.pending) ||
    window.confirm(
      '仍有未确认的草稿或操作结果。离开后请查询项目状态，确认没有重复提交。仍要离开吗？',
    )
  )
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
function submit() {
  const value = prompt.value
  void wb.submit(value, () => {
    if (prompt.value === value) prompt.value = ''
  })
}
function submitBuildRepair(proposal: BuildRepairProposal, description: string) {
  const context =
    wb.project && wb.head && session.ready && session.identity?.userId === wb.project.ownerId
      ? { projectId: wb.project.id, ownerId: wb.project.ownerId, head: wb.head }
      : null
  if (!matchesBuildRepair(proposal, context, wb.selectedBuild) || description.length > 1000) return
  void wb.submit(buildRepairPrompt(proposal, description), () => {
    toolsOpen.value = false
    area.value = 'conversation'
  })
}
function versionLabel(id: string | null | undefined) {
  if (!id) return '暂无'
  const version = wb.versions.find((v) => v.id === id)
  return version ? `V${version.versionNo}` : id.slice(0, 8)
}
function compare() {
  tab.value = 'diff'
  if (wb.selectedVersion?.basedOnId) void wb.sourceBrowser.compare(wb.selectedVersion.basedOnId)
}
function restore() {
  if (window.confirm('把选中历史源码追加为新版本？当前运行和业务数据不会回滚。')) void wb.restore()
}
function discardIntent() {
  if (window.confirm('请先确认任务与运行记录，未知结果可能已经执行。放弃原请求重试入口？'))
    wb.intent.clear()
}
function reloadDraft() {
  if (window.confirm('用服务器草稿替换当前输入？请先复制需要保留的内容。')) void autosave.reload()
}
async function showFile(version: string, path: string) {
  wb.selectedVersionId = version
  tab.value = 'source'
  area.value = 'engineering'
  toolsOpen.value = false
  await nextTick()
  void wb.sourceBrowser.selectFile(path)
}
function publishLocally() {
  toolsOpen.value = true
  area.value = 'engineering'
  if (wb.selectedBuild?.status === 'SUCCEEDED' && !wb.readingMigration) void wb.inspectMigration()
}
</script>
<template>
  <section class="engineering-page" aria-label="完整工程工作台">
    <header class="engineering-header">
      <div class="engineering-title">
        <RouterLink
          v-if="wb.project?.currentVersionId"
          :to="`/projects/${projectId}/legacy`"
          class="subtle-link"
          >查看旧版与存档</RouterLink
        >
        <RouterLink v-else to="/projects" class="subtle-link">← 我的项目</RouterLink>
        <h1 tabindex="-1">{{ wb.project?.name ?? '完整工程工作台' }}</h1>
      </div>
      <div class="engineering-actions">
        <button
          type="button"
          class="button primary"
          :disabled="!wb.capabilities?.enabled || !!wb.capabilities.archived || !!wb.intent.pending"
          @click="publishLocally"
        >
          本地发布
        </button>
        <span class="outline-badge">源码 {{ versionLabel(wb.head?.sourceVersionId) }}</span
        ><span class="outline-badge">运行 {{ versionLabel(wb.serving?.sourceVersionId) }}</span
        ><button
          type="button"
          class="button secondary"
          :disabled="wb.refreshing || wb.loading || !session.ready"
          @click="wb.capabilities?.enabled ? wb.refresh() : wb.load()"
        >
          {{ wb.refreshing ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>
    <p class="engineering-banner">Claude Code + DeepSeek · 访客与登录账号均可开发自己的完整工程</p>
    <p v-if="wb.project?.currentVersionId" class="engineering-banner">
      首次开发将参考旧版源码迁移页面和业务。旧版本、对话与原存档继续保留；新工程使用独立运行库，账号业务需通过后端接口实际验收。
    </p>
    <p v-if="wb.loading" role="status" class="engineering-banner">正在打开完整工程…</p>
    <div v-if="wb.error || wb.intent.error" role="alert" class="engineering-banner inline-error">
      {{ wb.error || wb.intent.error }}
    </div>
    <p v-if="wb.notice" role="status" class="engineering-banner">{{ wb.notice }}</p>
    <div v-if="wb.intent.pending && !wb.intent.busy" class="engineering-banner engineering-actions">
      <span>{{ wb.intent.pending }}：请查询状态，或重试同一请求。</span
      ><button type="button" @click="wb.intent.retry">重试原请求</button
      ><button type="button" @click="discardIntent">放弃重试</button>
    </div>
    <section v-if="wb.capabilities && !wb.capabilities.enabled" class="engineering-empty">
      <h2>完整工程工作台暂不可用</h2>
      <p>完整工程服务尚不可用，请检查服务配置或稍后重试。</p>
      <code>{{ wb.capabilities.reason }}</code
      ><RouterLink to="/projects" class="button secondary">返回我的项目</RouterLink>
    </section>
    <template v-else-if="wb.capabilities?.enabled">
      <p v-if="wb.capabilities.archived" class="engineering-banner">
        项目已归档，可以查看历史和请求停止；请先取消归档再继续开发。
      </p>
      <div class="engineering-mobile-switch" role="group" aria-label="工作台区域">
        <button
          type="button"
          :aria-pressed="area === 'conversation'"
          @click="area = 'conversation'"
        >
          需求与任务</button
        ><button type="button" :aria-pressed="area === 'engineering'" @click="area = 'engineering'">
          工程与预览
        </button>
      </div>
      <div class="engineering-grid" :data-area="area">
        <section class="engineering-conversation" aria-label="需求与任务">
          <div class="engineering-section-heading">
            <h2>需求与任务</h2>
            <span class="muted">最近 50 次提交</span>
          </div>
          <div
            ref="messages"
            class="engineering-scroll engineering-messages"
            tabindex="0"
            aria-label="需求记录"
          >
            <div v-if="!wb.head?.sourceVersionId" class="engineering-empty">
              <h3>从完整工程开始</h3>
              <p>
                直接提交下方需求即可准备 Vue + Spring Boot 工程并开始开发，也可以先初始化查看源码。
              </p>
              <button
                type="button"
                class="button primary"
                :disabled="
                  !!wb.intent.pending ||
                  !wb.capabilities.sourceReady ||
                  !wb.capabilities.agentEnabled ||
                  wb.capabilities.archived ||
                  (wb.operation !== null &&
                    !['COMMITTED', 'FAILED', 'CANCELLED'].includes(wb.operation.status))
                "
                @click="wb.initialize"
              >
                初始化工程
              </button>
              <p v-if="!wb.capabilities.sourceReady" class="muted">源码服务尚未就绪。</p>
              <p v-else-if="!wb.capabilities.agentEnabled" class="muted">
                工程初始化和开发需要启用 Agent 服务。
              </p>
            </div>
            <p v-if="wb.operation" role="status">
              源码操作：{{ statusLabel(wb.operation.status) }}
              <span v-if="wb.operation.errorCode">{{ wb.operation.errorCode }}</span>
            </p>
            <p v-if="wb.head?.sourceVersionId && !wb.agents.length" class="muted">
              工程源码已就绪。在下方描述功能或修改要求；已有业务功能需要通过实际预览确认。
            </p>
            <article
              v-for="task in [...wb.agents].reverse()"
              :key="task.id"
              class="engineering-message"
            >
              <div class="engineering-section-heading">
                <strong>你</strong><span>{{ statusLabel(task.status) }}</span>
              </div>
              <p class="engineering-prompt">{{ task.prompt }}</p>
              <p class="muted">
                基于 {{ versionLabel(task.baseSourceVersionId)
                }}<span v-if="task.resultVersionId">
                  → {{ versionLabel(task.resultVersionId) }}</span
                ><span v-if="task.cancelRequested"> · 已请求取消</span>
              </p>
              <p v-if="task.errorCode" class="inline-error">{{ task.errorCode }}</p>
              <div class="engineering-actions">
                <button type="button" @click="wb.showEvents('AGENT', task.id)">任务事件</button
                ><button
                  v-if="!terminal(task.status)"
                  type="button"
                  :disabled="!!wb.intent.pending || task.cancelRequested"
                  @click="wb.command('取消开发', `/agent/tasks/${task.id}/cancel`, {})"
                >
                  请求取消
                </button>
              </div>
            </article>
            <section v-if="wb.eventTarget" class="engineering-events" aria-label="任务事件">
              <h3>任务事件 · {{ wb.eventTarget.id.slice(0, 8) }}</h3>
              <p v-if="wb.eventsError" role="alert" class="inline-error">{{ wb.eventsError }}</p>
              <ol>
                <li v-for="event in wb.events" :key="event.sequence">
                  {{ event.sequence }} · {{ event.description }}
                </li>
              </ol>
              <p v-if="!wb.events.length && !wb.eventsLoading" class="muted">暂无事件。</p>
              <button type="button" :disabled="wb.eventsLoading" @click="wb.loadEvents()">
                {{ wb.eventsLoading ? '读取中…' : wb.eventsMore ? '加载后续事件' : '刷新事件' }}
              </button>
            </section>
          </div>
          <form class="engineering-composer" @submit.prevent="submit">
            <label for="engineering-prompt">{{
              wb.head?.sourceVersionId ? '描述下一步修改' : '先写下你的想法'
            }}</label>
            <textarea
              id="engineering-prompt"
              v-model="prompt"
              rows="3"
              maxlength="8000"
              placeholder="例如：增加搜索和分类，保留已有的数据与功能…"
            />
            <div class="engineering-actions">
              <small role="status">{{ draftLabel }}</small
              ><button
                class="button primary"
                type="submit"
                :disabled="
                  !wb.canSubmit || !wb.capabilities.agentEnabled || !prompt.trim() || wb.intent.busy
                "
              >
                {{ wb.intent.busy ? '正在提交…' : '提交开发需求' }}
              </button>
            </div>
            <p v-if="!wb.capabilities.agentEnabled" class="muted">完整工程 Agent 尚未启用。</p>
            <p v-if="autosave.error" role="alert" class="inline-error">{{ autosave.error }}</p>
            <button v-if="autosave.status === 'error'" type="button" @click="autosave.retry">
              重试保存草稿</button
            ><button v-if="autosave.status === 'conflict'" type="button" @click="reloadDraft">
              读取服务器草稿
            </button>
          </form>
        </section>
        <section class="engineering-main" aria-label="工程与预览">
          <div class="engineering-toolbar">
            <div class="engineering-actions">
              <div role="group" aria-label="工程内容">
                <button type="button" :aria-pressed="tab === 'preview'" @click="tab = 'preview'">
                  预览</button
                ><button type="button" :aria-pressed="tab === 'source'" @click="tab = 'source'">
                  源码</button
                ><button
                  type="button"
                  :aria-pressed="tab === 'diff'"
                  :disabled="!wb.selectedVersion?.basedOnId"
                  @click="compare"
                >
                  差异
                </button>
              </div>
              <label class="engineering-select"
                >源码<select v-model="wb.selectedVersionId">
                  <option value="" disabled>尚未初始化</option>
                  <option v-for="version in wb.versions" :key="version.id" :value="version.id">
                    V{{ version.versionNo
                    }}{{ version.id === wb.head?.sourceVersionId ? ' · 当前' : '' }}
                  </option>
                </select></label
              ><button v-if="wb.nextVersion" type="button" @click="wb.moreVersions">
                更早版本
              </button>
            </div>
            <div class="engineering-actions">
              <button
                type="button"
                class="button primary"
                :disabled="!wb.serving || wb.preview.opening || !wb.capabilities.deliveryEnabled"
                @click="wb.serving && wb.preview.open(wb.serving.id)"
              >
                {{ wb.preview.opening ? '打开中…' : '打开运行预览' }}</button
              ><button
                type="button"
                class="button secondary"
                :aria-expanded="toolsOpen"
                aria-controls="engineering-tools"
                @click="toolsOpen = !toolsOpen"
              >
                构建与运行 {{ toolsOpen ? '▴' : '▾' }}</button
              ><button
                type="button"
                class="button secondary"
                :disabled="
                  !wb.canEdit ||
                  !wb.selectedVersionId ||
                  wb.selectedVersionId === wb.head?.sourceVersionId ||
                  !wb.capabilities.deliveryEnabled
                "
                @click="restore"
              >
                恢复所选源码
              </button>
            </div>
            <div class="engineering-actions engineering-downloads">
              <button
                type="button"
                :disabled="!wb.preview.preview || wb.downloading"
                @click="wb.preview.preview && wb.download('previews', wb.preview.preview.previewId)"
              >
                下载当前预览源码</button
              ><button
                type="button"
                :disabled="
                  wb.selectedBuild?.status !== 'SUCCEEDED' ||
                  !wb.capabilities.deliveryEnabled ||
                  wb.downloading
                "
                @click="wb.download('builds', wb.selectedBuildId)"
              >
                下载选中构建源码</button
              ><small class="muted">构建 {{ wb.selectedBuildId.slice(0, 8) || '暂无' }}</small>
            </div>
            <LocalApplicationLink
              v-if="wb.project && wb.capabilities.deliveryEnabled"
              :project-id="projectId"
              :owner-id="wb.project.ownerId"
              :run="wb.serving"
            />
            <div
              v-if="toolsOpen"
              id="engineering-tools"
              class="engineering-tools-popover"
              @keydown.esc="toolsOpen = false"
            >
              <EngineeringRunPanel
                :workbench="wb"
                @source-file="showFile"
                @build="wb.selectedBuildId = $event"
                @repair="submitBuildRepair"
              />
            </div>
          </div>
          <div v-show="tab === 'preview'" class="engineering-preview">
            <p v-if="wb.preview.error" role="alert" class="engineering-banner inline-error">
              {{ wb.preview.error }}
            </p>
            <template v-if="wb.preview.preview"
              ><div class="engineering-preview-caption">
                运行源码 {{ versionLabel(wb.preview.preview.identity.sourceVersionId) }} · 构建
                {{ wb.preview.preview.identity.buildId.slice(0, 8)
                }}<span
                  v-if="wb.preview.preview.identity.sourceVersionId !== wb.head?.sourceVersionId"
                >
                  · 与当前源码不同</span
                ><button type="button" @click="wb.preview.clear()">关闭预览</button>
              </div>
              <iframe
                :key="wb.preview.preview.previewId"
                :src="wb.preview.preview.iframeUrl"
                title="完整工程运行预览"
                sandbox="allow-scripts allow-forms"
                referrerpolicy="no-referrer"
            /></template>
            <div v-else class="engineering-empty">
              <span class="engineering-empty-mark" aria-hidden="true">◫</span>
              <h2>让源码真正运行起来</h2>
              <p>初始化工程 → 提交需求 → 构建 → 核对迁移 → 启动应用。</p>
              <p class="muted">应用就绪后在这里预览。源码更新不会自动替换正在运行的页面。</p>
              <button type="button" class="button secondary" @click="toolsOpen = true">
                打开构建与运行
              </button>
            </div>
          </div>
          <EngineeringSourcePanel
            v-show="tab !== 'preview'"
            :browser="wb.sourceBrowser"
            :diff="tab === 'diff'"
          />
        </section>
      </div>
    </template>
  </section>
</template>
