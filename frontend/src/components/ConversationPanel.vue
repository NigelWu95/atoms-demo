<script setup lang="ts">
import { computed } from 'vue'
import type { Project } from '../api/projects'
import { runLabel } from '../api/runs'
import { useGeneration } from '../composables/useGeneration'
import PromptComposer from './PromptComposer.vue'

const props = defineProps<{ project: Project; modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; changed: [project: Project] }>()
const {
  run,
  messages,
  cursor,
  availability,
  connection,
  loading,
  busy,
  error,
  pending,
  active,
  canSubmit,
  refresh,
  submit,
  cancel,
  older,
} = useGeneration(
  () => props.project,
  () => props.modelValue,
  (value) => emit('update:modelValue', value),
  (project) => emit('changed', project),
)
const configuration = computed(() => {
  if (!availability.value) return '正在读取生成配置…'
  if (!availability.value.workerEnabled) return '当前测试服务不执行生成，请使用 5173 普通工作台。'
  if (!availability.value.configured) return '未配置 DeepSeek API Key，请在服务端完成配置。'
  if (availability.value.remainingRequests < 2) return '剩余模型调用次数不足，请在服务端调整额度。'
  return `DeepSeek Flash · 最多剩余 ${availability.value.remainingRequests} 次请求；每轮通常 2 次，修复额外 1 次。`
})
const roles: Record<string, string> = {
  USER: '你',
  PLANNER: '规划助手',
  BUILDER: '构建助手',
  SYSTEM: '系统',
}
const connectionLabel = computed(
  () =>
    ({
      idle: '',
      connecting: '正在连接实时进度…',
      connected: '实时进度已连接',
      reconnecting: '进度连接中断，正在重连并补齐事件…',
      paused: '实时进度已暂停，可刷新恢复；后台任务继续执行。',
    })[connection.value],
)
</script>

<template>
  <section class="conversation-panel" aria-labelledby="conversation-title">
    <div class="panel-heading">
      <h2 id="conversation-title">需求与对话</h2>
      <span role="status" aria-live="polite" class="muted">{{ runLabel(run) }}</span>
    </div>
    <div class="generation-conversation">
      <p class="muted generation-config">{{ configuration }}</p>
      <p v-if="connectionLabel" role="status" aria-live="polite" class="muted">
        {{ connectionLabel }}
      </p>
      <div class="generation-actions">
        <button type="button" class="button secondary" :disabled="busy || loading" @click="refresh">
          刷新生成状态
        </button>
        <button
          v-if="active"
          type="button"
          class="button secondary"
          :disabled="busy"
          @click="cancel"
        >
          取消生成
        </button>
      </div>
      <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
      <div v-if="pending" class="generation-pending">
        <p>提交结果尚未确认。重试会发送原需求和原版本基线，不重复创建任务。</p>
        <button type="button" class="button secondary" :disabled="busy" @click="submit">
          重试原提交
        </button>
      </div>
      <button
        v-if="cursor"
        class="button secondary"
        type="button"
        :disabled="loading"
        @click="older"
      >
        更早的消息
      </button>
      <div v-if="!messages.length" class="generation-empty">
        <div class="agent-avatar" aria-hidden="true">✳</div>
        <h3>把想法变成可运行的应用</h3>
        <p>描述用途与关键操作。规划、构建和结果会保存在对话中；生成时可以继续使用上一版预览。</p>
      </div>
      <article
        v-for="message in messages"
        :key="message.id"
        class="generation-message"
        :class="{ 'user-message': message.role === 'USER' }"
      >
        <h3>{{ roles[message.role] }}</h3>
        <p>{{ message.content }}</p>
      </article>
      <p v-if="active" class="muted">
        后台正在执行，关闭页面不会取消。刷新后可恢复；阶段按实际状态更新。
      </p>
      <p v-if="run?.status === 'SUCCEEDED'" class="muted">
        新代码版本已保存。请实际操作预览确认功能，生成完成不代表浏览器检查已通过。
      </p>
      <p
        v-if="run?.status === 'FAILED' || run?.status === 'INTERRUPTED'"
        role="alert"
        class="inline-error"
      >
        {{ run.errorMessage }}（{{ run.errorCode }}）
      </p>
    </div>
    <PromptComposer
      :model-value="modelValue"
      :disabled="!canSubmit"
      label="继续完善你的需求"
      :action-label="project.currentVersionId ? '修改应用' : '生成应用'"
      hint="需求与当前源码将发送给 DeepSeek；请勿输入密钥或敏感资料。"
      @update:model-value="emit('update:modelValue', $event)"
      @submit="submit"
    />
  </section>
</template>
