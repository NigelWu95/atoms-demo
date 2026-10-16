<script setup lang="ts">
import type { PendingGeneration } from '../runtime/generationRecovery'

defineProps<{
  pending?: PendingGeneration
  savedAt?: number
  blocked: boolean
  unavailable: boolean
  disabled: boolean
}>()
const emit = defineEmits<{ retry: []; discard: [] }>()
</script>

<template>
  <section
    v-if="pending || blocked || unavailable || savedAt !== undefined"
    class="generation-pending recovery-panel"
    aria-label="提交恢复"
  >
    <p v-if="pending" role="status">上次提交结果待确认，刷新不会自动重发。</p>
    <p v-if="blocked" role="alert">
      恢复记录已过期、损坏或无法读取，不能安全重试。请先刷新生成状态并核对任务历史，再决定是否放弃本地记录。
    </p>
    <p v-if="unavailable" role="alert">
      浏览器存储不可用，刷新后无法保证恢复。
      <template v-if="pending && !blocked"
        >当前页面的原请求仍可手动重试，请勿在确认结果前关闭页面。</template
      >
      <template v-else>请先核对任务历史，不要把缓存缺失当作尚未提交。</template>
    </p>
    <template v-if="pending">
      <p>
        重试沿用原请求。已被接受则返回原任务；若上次未到达服务端，本次可能创建任务并消耗模型额度。
      </p>
      <details>
        <summary>查看原提交内容</summary>
        <p class="recovery-prompt">{{ pending.body.prompt }}</p>
        <p>
          操作：{{
            pending.preserveDraft
              ? '预览修复'
              : pending.body.intent === 'CREATE'
                ? '生成应用'
                : '修改应用'
          }}
        </p>
        <p>
          版本基线：{{ pending.body.baseVersionId ?? '初始创建' }} · 项目修订
          {{ pending.body.expectedProjectRevision }}
        </p>
      </details>
      <button
        type="button"
        class="button secondary"
        data-action="retry"
        :disabled="disabled || blocked"
        @click="emit('retry')"
      >
        重试原提交
      </button>
    </template>
    <p v-else-if="savedAt !== undefined && !blocked">
      原提交已确认，仅保留修复任务标记，避免失败修复回填普通输入框；可主动清除以释放恢复记录容量。
    </p>
    <p v-if="savedAt !== undefined" class="muted">
      记录时间：<time :datetime="new Date(savedAt).toISOString()">{{
        new Date(savedAt).toLocaleString()
      }}</time
      >。仅本标签页恢复，有效期 24 小时，重试不延期。
    </p>
    <template v-if="pending || blocked || savedAt !== undefined">
      <p class="muted">放弃本地记录不会取消后台任务。再次提交新需求可能重复生成并消耗额度。</p>
      <button
        type="button"
        class="button secondary"
        data-action="discard"
        :disabled="disabled"
        @click="emit('discard')"
      >
        放弃本地恢复记录
      </button>
    </template>
  </section>
</template>

<style scoped>
.recovery-panel {
  overflow-wrap: anywhere;
}
.recovery-panel summary {
  cursor: pointer;
  min-height: 44px;
  display: list-item;
  align-content: center;
}
.recovery-prompt {
  white-space: pre-wrap;
  max-height: 240px;
  overflow: auto;
}
</style>
