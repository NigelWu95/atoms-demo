<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    disabled?: boolean
    actionLabel?: string
    label?: string
    hint?: string
    compact?: boolean
  }>(),
  {
    disabled: false,
    actionLabel: '进入工作台',
    label: '描述你的应用想法',
    hint: 'Enter 继续 · Shift + Enter 换行',
    compact: false,
  },
)
const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [prompt: string]
}>()
const composing = ref(false)
const canSubmit = computed(
  () => !props.disabled && props.modelValue.trim().length > 0 && props.modelValue.length <= 8000,
)
function submit() {
  if (canSubmit.value && !composing.value) emit('submit', props.modelValue.trim())
}
function keydown(event: KeyboardEvent) {
  // Vue v-model defers updates during IME; Enter must also respect composition.
  // https://vuejs.org/guide/essentials/forms.html#text
  if (
    event.key !== 'Enter' ||
    event.shiftKey ||
    event.isComposing ||
    composing.value ||
    event.keyCode === 229
  )
    return
  event.preventDefault()
  submit()
}
</script>

<template>
  <form class="composer" :class="{ 'compact-composer': compact }" @submit.prevent="submit">
    <label class="input-label" for="prompt-input">{{ label }}</label>
    <textarea
      id="prompt-input"
      :value="modelValue"
      maxlength="8000"
      rows="4"
      placeholder="例如：做一个简洁的待办工具，可以添加、完成和筛选任务…"
      aria-describedby="prompt-hint"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      @compositionstart="composing = true"
      @compositionend="composing = false"
      @keydown="keydown"
    />
    <div class="composer-footer">
      <details v-if="compact" class="composer-help">
        <summary>数据与隐私说明</summary>
        <p id="prompt-hint" class="muted">{{ hint }}</p>
      </details>
      <span v-else id="prompt-hint" class="muted">{{ hint }}</span>
      <button type="submit" class="button primary" :disabled="!canSubmit">
        {{ actionLabel }} <span aria-hidden="true">↗</span>
      </button>
    </div>
  </form>
</template>
