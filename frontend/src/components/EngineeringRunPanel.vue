<script setup lang="ts">
import { computed, type UnwrapNestedRefs } from 'vue'
import type { useEngineeringWorkbench } from '../composables/useEngineeringWorkbench'
import { statusLabel, terminal } from '../api/projectWorkbenchTasks'
import EngineeringBuildRepair from './EngineeringBuildRepair.vue'
import type { BuildRepairProposal, BuildRepairContext } from '../runtime/buildRepair'
const props = defineProps<{
  workbench: UnwrapNestedRefs<ReturnType<typeof useEngineeringWorkbench>>
}>()
const emit = defineEmits<{
  sourceFile: [version: string, path: string]
  build: [id: string]
  repair: [proposal: BuildRepairProposal, description: string]
}>()
const repairContext = computed<BuildRepairContext | null>(() => {
  const { project, head } = props.workbench
  return project && head ? { projectId: project.id, ownerId: project.ownerId, head } : null
})
const buildSelection = computed({
  get: () => props.workbench.selectedBuildId,
  set: (value: string) => emit('build', value),
})
const blocked = computed(
  () => !!props.workbench.intent.pending || !!props.workbench.capabilities?.archived,
)
function approve() {
  if (
    window.confirm('批准所展示构建的待执行数据库迁移？请先查看 SQL。此操作不会回滚现有业务数据。')
  )
    void props.workbench.approveMigration()
}
function start() {
  if (
    window.confirm(
      props.workbench.activeRun
        ? '先停止当前应用，再启动选中的构建？期间应用会暂时不可访问。'
        : '在本机启动选中构建，并按已核对的迁移更新绑定数据库？',
    )
  )
    void props.workbench.start()
}
</script>
<template>
  <div class="engineering-run-panel engineering-scroll">
    <h3>构建与运行</h3>
    <p class="muted">
      构建只使用选中的源码。运行使用项目已配置的业务数据库；预览与下载绑定具体构建。
    </p>
    <div class="engineering-actions">
      <button
        type="button"
        class="button secondary"
        :disabled="blocked || !workbench.canEdit"
        @click="workbench.prepare"
      >
        准备依赖锁
      </button>
      <button
        type="button"
        class="button primary"
        :disabled="blocked || !workbench.selectedVersionId || !workbench.runtime.coordinatorReady"
        @click="workbench.build"
      >
        构建选中源码
      </button>
    </div>
    <p v-if="!workbench.runtime.coordinatorReady" class="inline-error">
      构建和运行服务尚未就绪，请检查服务配置后刷新。
    </p>
    <label class="engineering-select"
      >构建记录<select v-model="buildSelection" aria-label="构建记录">
        <option value="" disabled>尚无构建</option>
        <option v-for="build in workbench.builds" :key="build.id" :value="build.id">
          {{ statusLabel(build.status) }} · {{ build.id.slice(0, 8) }} · 源码
          {{ build.sourceVersionId.slice(0, 8) }}
        </option>
      </select></label
    >
    <div v-if="workbench.selectedBuild" class="engineering-actions">
      <button
        type="button"
        class="button secondary"
        @click="workbench.showEvents('BUILD', workbench.selectedBuild.id)"
      >
        查看构建事件
      </button>
      <button
        v-if="!terminal(workbench.selectedBuild.status)"
        type="button"
        class="button secondary"
        :disabled="!!workbench.intent.pending"
        @click="workbench.command('取消构建', `/builds/${workbench.selectedBuild.id}/cancel`, {})"
      >
        请求取消构建
      </button>
      <button
        v-else-if="workbench.selectedBuild.status === 'SUCCEEDED'"
        type="button"
        class="button secondary"
        :disabled="
          blocked ||
          workbench.readingMigration ||
          !workbench.runtime.databaseBound ||
          !workbench.runtime.coordinatorReady
        "
        @click="workbench.inspectMigration"
      >
        {{ workbench.readingMigration ? '核对中…' : '核对数据库迁移' }}
      </button>
    </div>
    <p v-if="workbench.selectedBuild?.errorCode" class="inline-error">
      {{ workbench.selectedBuild.errorCode }}
    </p>
    <EngineeringBuildRepair
      v-if="workbench.selectedBuild?.status === 'FAILED'"
      :context="repairContext"
      :build="workbench.selectedBuild"
      :disabled="!workbench.canEdit || !workbench.capabilities?.agentEnabled"
      @submit="(proposal, description) => emit('repair', proposal, description)"
    />
    <button
      v-if="workbench.taskCursors.builds"
      type="button"
      @click="workbench.moreTasks('builds')"
    >
      加载更早构建
    </button>
    <p v-if="!workbench.runtime.databaseBound" class="muted">
      该项目尚未绑定运行数据库。维护者配置后才可启动应用。
    </p>
    <section v-if="workbench.migration" class="engineering-migration" aria-label="数据库迁移核对">
      <h3>待运行构建 {{ workbench.migration.buildId.slice(0, 8) }}</h3>
      <p>
        {{
          workbench.migration.decision === 'REVIEW_REQUIRED'
            ? '有新增迁移，需要查看并明确批准。'
            : workbench.migration.decision === 'INITIALIZE'
              ? '绑定数据库为空，首次启动将创建表。'
              : '数据库历史与该构建一致。'
        }}
      </p>
      <ul>
        <li v-for="file in workbench.migration.files" :key="file.path">
          <button
            type="button"
            class="subtle-link"
            @click="emit('sourceFile', workbench.migration.sourceVersionId, file.path)"
          >
            {{ file.applied ? '已应用' : '待执行' }} · {{ file.path.split('/').at(-1) }}
          </button>
        </li>
      </ul>
      <p class="engineering-digest">
        历史 {{ workbench.migration.historySha256 }}<br />目标
        {{ workbench.migration.targetSha256 }}
      </p>
      <div class="engineering-actions">
        <button
          v-if="workbench.migration.decision === 'REVIEW_REQUIRED'"
          type="button"
          class="button secondary"
          :disabled="blocked || !!workbench.approval"
          @click="approve"
        >
          {{ workbench.approval ? '已批准本次迁移' : '批准本次迁移' }}
        </button>
        <button
          type="button"
          class="button primary"
          :disabled="
            blocked || (workbench.migration.decision === 'REVIEW_REQUIRED' && !workbench.approval)
          "
          @click="start"
        >
          {{ workbench.activeRun ? '替换当前运行' : '启动应用' }}
        </button>
      </div>
    </section>
    <h3>运行记录</h3>
    <p v-if="!workbench.runs.length" class="muted">尚未启动应用。</p>
    <div v-for="run in workbench.runs" :key="run.id" class="engineering-task-row">
      <span
        >{{ statusLabel(run.status) }} · {{ run.id.slice(0, 8)
        }}<small v-if="run.stopRequested"> · 已请求停止</small
        ><small v-if="run.errorCode"> · {{ run.errorCode }}</small></span
      >
      <div class="engineering-actions">
        <button type="button" @click="workbench.showEvents('RUN', run.id)">运行事件</button>
        <button
          v-if="run.status === 'READY'"
          type="button"
          :disabled="workbench.preview.opening || !workbench.capabilities?.deliveryEnabled"
          @click="workbench.preview.open(run.id)"
        >
          打开此运行预览
        </button>
        <button
          v-if="!run.finishedAt && run.status !== 'NEEDS_RECONCILIATION'"
          type="button"
          :disabled="!!workbench.intent.pending || run.stopRequested"
          @click="workbench.command('停止应用', `/runs/${run.id}/stop`, {})"
        >
          请求停止
        </button>
      </div>
    </div>
    <button v-if="workbench.taskCursors.runs" type="button" @click="workbench.moreTasks('runs')">
      加载更早运行
    </button>
    <h3>依赖准备记录</h3>
    <p v-if="!workbench.preparations.length" class="muted">
      依赖不变时可直接构建。新增 npm 依赖需要先准备受控锁文件。
    </p>
    <div v-for="task in workbench.preparations" :key="task.id" class="engineering-task-row">
      <span
        >{{ statusLabel(task.status) }} · {{ task.id.slice(0, 8)
        }}<small v-if="task.errorCode"> · {{ task.errorCode }}</small></span
      >
      <div class="engineering-actions">
        <button type="button" @click="workbench.showEvents('PREPARATION', task.id)">依赖事件</button
        ><button
          v-if="!terminal(task.status)"
          type="button"
          :disabled="!!workbench.intent.pending"
          @click="workbench.command('取消依赖准备', `/preparations/${task.id}/cancel`, {})"
        >
          请求取消
        </button>
      </div>
    </div>
    <button
      v-if="workbench.taskCursors.preparations"
      type="button"
      @click="workbench.moreTasks('preparations')"
    >
      加载更早依赖记录
    </button>
  </div>
</template>
