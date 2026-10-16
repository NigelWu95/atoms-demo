<script setup lang="ts">
import { computed, onMounted, ref, useId, watch } from 'vue'
import { repairPrompt, type RepairProposal } from '../runtime/previewRepair'
const props = defineProps<{ proposal: RepairProposal; disabled: boolean; submitting: boolean }>()
const emit = defineEmits<{ confirm: [description: string]; dismiss: [] }>()
const description = ref('')
const consent = ref(false)
const heading = ref<HTMLHeadingElement | null>(null)
const inputId = useId()
const prompt = computed(() => repairPrompt(props.proposal, description.value))
const canConfirm = computed(
  () => !props.disabled && !props.submitting && consent.value && description.value.length <= 1000,
)
watch(description, () => {
  consent.value = false
})
watch(
  () => props.proposal,
  () => {
    description.value = ''
    consent.value = false
    heading.value?.focus()
  },
)
onMounted(() => heading.value?.focus())
</script>

<template>
  <section class="repair-confirmation" aria-label="确认预览修复">
    <h3 ref="heading" tabindex="-1">确认修复建议 · V{{ proposal.versionNo }}</h3>
    <p>仅发送固定诊断类别和你补充的说明，不自动附带原始报错、堆栈或业务数据。</p>
    <label :for="inputId">补充问题描述（可选，最多 1000 字符）</label>
    <textarea
      :id="inputId"
      v-model="description"
      maxlength="1000"
      rows="3"
      :disabled="submitting"
    />
    <p>将发送的修复需求：</p>
    <pre tabindex="0" aria-label="将发送的修复需求">{{ prompt }}</pre>
    <p>
      确认后，以上需求、当前源码及有效历史需求将发送给 DeepSeek。通常调用 2 次，契约修复最多额外 1
      次；不自动发布，生成后仍需验证。
    </p>
    <label class="repair-consent"
      ><input
        v-model="consent"
        type="checkbox"
        :disabled="submitting"
      />我已确认内容并同意调用模型</label
    >
    <p v-if="disabled" class="muted">
      当前不能提交，请等待状态读取完毕，并检查生成配置、活动任务或未确认的提交结果。
    </p>
    <div class="repair-actions">
      <button
        type="button"
        class="button primary"
        data-action="confirm-repair"
        :disabled="!canConfirm"
        @click="emit('confirm', description)"
      >
        确认并请求修复
      </button>
      <button
        type="button"
        class="button secondary"
        data-action="dismiss-repair"
        :disabled="submitting"
        @click="emit('dismiss')"
      >
        取消建议
      </button>
    </div>
  </section>
</template>

<style scoped>
.repair-confirmation {
  padding: 1rem;
  margin-block: 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 0.8125rem;
  overflow-wrap: anywhere;
}
h3:focus,
pre:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
label {
  display: block;
  margin-block: 0.75rem;
}
textarea {
  width: 100%;
  resize: vertical;
  background: var(--surface);
  color: var(--text);
  padding: 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
}
pre {
  white-space: pre-wrap;
  max-height: 15rem;
  overflow: auto;
  font: inherit;
  padding: 0.75rem;
  background: var(--surface);
}
.repair-consent {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 44px;
}
.repair-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
