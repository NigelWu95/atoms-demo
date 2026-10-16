<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ stage: string | null; status: string | null }>()
const steps = ['排队', '规划需求', '生成代码', '保存版本']
const current = computed(
  () =>
    ({ QUEUED: 0, PLANNING: 1, BUILDING: 2, REPAIRING: 2, SUCCEEDED: 3 })[props.stage ?? ''] ?? -1,
)
const stopped = computed(() => ['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(props.status ?? ''))
</script>
<template>
  <div class="generation-progress" aria-label="生成进度">
    <ol>
      <li
        v-for="(step, index) in steps"
        :key="step"
        :aria-current="!stopped && current === index ? 'step' : undefined"
        :class="{ done: !stopped && (index < current || status === 'SUCCEEDED') }"
      >
        <span aria-hidden="true">{{ index + 1 }}</span
        >{{ step }}
      </li>
    </ol>
    <p v-if="stage === 'REPAIRING'" role="status">产物校验发现问题，正在进行一次修复。</p>
    <p v-if="stopped" role="status">本轮未完成，已有可用版本保持不变。</p>
    <p v-if="status === 'SUCCEEDED'">代码已保存；仍需要实际操作预览确认功能。</p>
  </div>
</template>
<style scoped>
.generation-progress {
  padding: 0.75rem 0;
  font-size: 0.8125rem;
}
ol {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
}
li {
  padding: 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
}
li span {
  margin-right: 0.375rem;
}
li[aria-current] {
  font-weight: 700;
  border-color: currentColor;
}
.done::after {
  content: ' ✓';
}
</style>
