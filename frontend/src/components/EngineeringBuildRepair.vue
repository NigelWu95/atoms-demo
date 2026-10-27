<script setup lang="ts">
import { computed, nextTick, reactive, ref, useId, watch } from 'vue'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'
import {
  buildRepairBlock,
  buildRepairSelection,
  buildRepairPrompt,
  matchesBuildRepair,
  type BuildRepairContext,
  type BuildRepairProposal,
} from '../runtime/buildRepair'
import { useBuildRepair } from '../composables/useBuildRepair'

const props = defineProps<{
  context: BuildRepairContext | null
  build: ExecutionTask
  disabled: boolean
}>()
const emit = defineEmits<{ submit: [proposal: BuildRepairProposal, description: string] }>()
const repair = reactive(useBuildRepair(() => buildRepairSelection(props.context, props.build)))
const description = ref(''),
  heading = ref<HTMLElement | null>(null),
  trigger = ref<HTMLButtonElement | null>(null)
const inputId = useId()
const blocked = computed(() => buildRepairBlock(props.context, props.build))
const prompt = computed(() =>
  repair.proposal ? buildRepairPrompt(repair.proposal, description.value) : '',
)
watch(
  () => repair.proposal,
  async (value) => {
    description.value = ''
    if (value) {
      await nextTick()
      heading.value?.focus()
    }
  },
)
function submit() {
  if (
    !repair.proposal ||
    props.disabled ||
    description.value.length > 1000 ||
    !matchesBuildRepair(repair.proposal, props.context, props.build)
  )
    return
  emit('submit', repair.proposal, description.value)
}
function dismiss() {
  repair.clear()
  trigger.value?.focus()
}
</script>
<template>
  <section class="engineering-build-repair" aria-label="构建修复建议">
    <p v-if="blocked" class="muted">{{ blocked }}</p>
    <button
      v-else
      ref="trigger"
      type="button"
      class="button secondary"
      :disabled="repair.loading || disabled"
      @click="repair.prepare"
    >
      {{ repair.loading ? '读取失败摘要…' : '准备修复需求' }}
    </button>
    <p v-if="repair.error" class="inline-error" role="alert">{{ repair.error }}</p>
    <div v-if="repair.proposal" class="engineering-repair-content">
      <h3 ref="heading" tabindex="-1">确认构建修复</h3>
      <p>修复建议基于当前源码。原需求草稿保持不变，确认提交后才调用 Agent。</p>
      <label :for="inputId">补充修复要求（可选）</label>
      <textarea
        :id="inputId"
        v-model="description"
        rows="3"
        maxlength="1000"
        :disabled="disabled"
      />
      <pre tabindex="0" aria-label="将发送的构建修复需求">{{ prompt }}</pre>
      <p class="muted">修复后需要重新构建并确认功能；当前运行的应用继续保留。</p>
      <div class="engineering-actions">
        <button
          type="button"
          class="button primary"
          :disabled="disabled || description.length > 1000"
          @click="submit"
        >
          确认提交修复
        </button>
        <button type="button" class="button secondary" @click="dismiss">取消建议</button>
      </div>
    </div>
  </section>
</template>
<style scoped>
.engineering-build-repair {
  border-top: 1px solid var(--border);
  padding-top: 12px;
}
.engineering-repair-content > * {
  margin-top: 12px;
}
label {
  display: block;
}
textarea {
  width: 100%;
  background: var(--surface);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px;
  resize: vertical;
  min-height: 72px;
  max-height: 180px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 220px;
  overflow: auto;
  font: inherit;
  padding: 12px;
  background: var(--bg);
}
h3:focus {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
</style>
