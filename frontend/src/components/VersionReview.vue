<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { getVersionReview, type VersionReview } from '../api/versionReview'
import { errorMessage } from '../api/client'
const props = defineProps<{ projectId: string; versionId: string }>()
const review = ref<VersionReview | null>(null)
const open = ref(false)
const loading = ref(false)
const error = ref('')
let epoch = 0
watch(
  () => [props.projectId, props.versionId],
  () => {
    epoch++
    review.value = null
    open.value = false
    loading.value = false
    error.value = ''
  },
)
onUnmounted(() => epoch++)
async function load() {
  const request = ++epoch
  loading.value = true
  error.value = ''
  try {
    const value = await getVersionReview(props.projectId, props.versionId)
    if (request === epoch) review.value = value
  } catch (failure) {
    if (request === epoch) error.value = errorMessage(failure)
  } finally {
    if (request === epoch) loading.value = false
  }
}
function toggle() {
  open.value = !open.value
  if (open.value && !review.value && !loading.value) void load()
}
</script>

<template>
  <section class="version-review" aria-label="版本修改记录">
    <button type="button" class="button secondary" :aria-expanded="open" @click="toggle">
      {{ open ? '收起修改记录' : '查看修改记录与差异' }}
    </button>
    <div v-if="open" :aria-busy="loading">
      <p v-if="loading" role="status">正在读取版本修改记录…</p>
      <p v-if="error" role="alert">
        {{ error }} <button type="button" class="button secondary" @click="load">重试读取</button>
      </p>
      <template v-if="review">
        <h3>{{ review.restoredFromId ? '历史版本恢复' : '本版修改摘要' }}</h3>
        <p>
          {{ review.baseVersionNo ? `相对 V${review.baseVersionNo}` : '首次生成' }}：{{
            review.changes.length ? `${review.changes.length} 个文件发生变化` : '源码与基线一致'
          }}。
        </p>
        <p v-if="review.request" class="request-text">对应需求：{{ review.request }}</p>
        <p v-else class="muted">
          {{ review.restoredFromId ? '由恢复操作产生，未调用模型。' : '此版本没有关联生成需求。' }}
        </p>
        <p class="muted">
          以下为已保存源码的真实对比，不代表功能测试已通过。业务数据不在此差异中。
        </p>
        <details v-for="change in review.changes" :key="change.file">
          <summary>
            {{ change.file }} · {{ change.before.length }} → {{ change.after.length }} 字符
          </summary>
          <div class="comparison">
            <div>
              <h4>修改前</h4>
              <pre
                tabindex="0"
                :aria-label="`${change.file} 修改前`"
              ><code>{{ change.before || '（空）' }}</code></pre>
            </div>
            <div>
              <h4>修改后</h4>
              <pre
                tabindex="0"
                :aria-label="`${change.file} 修改后`"
              ><code>{{ change.after || '（空）' }}</code></pre>
            </div>
          </div>
        </details>
      </template>
    </div>
  </section>
</template>

<style scoped>
.version-review {
  padding: 1rem;
  border-bottom: 1px solid var(--border);
  font-size: 0.875rem;
  overflow-wrap: anywhere;
}
.request-text {
  white-space: pre-wrap;
  max-height: 12rem;
  overflow: auto;
}
h3 {
  margin-top: 1rem;
}
p {
  margin-top: 0.5rem;
  line-height: 1.6;
}
summary {
  cursor: pointer;
  padding: 0.75rem 0;
}
.comparison {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}
h4 {
  margin: 0.5rem 0;
}
pre {
  overflow: auto;
  max-height: 18rem;
  padding: 0.75rem;
  border: 1px solid var(--border);
  font-size: 0.75rem;
}
@media (max-width: 768px) {
  .comparison {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
