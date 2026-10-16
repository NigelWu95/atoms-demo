<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { diffLines, withContext } from './lineDiff'

const props = defineProps<{ file: string; before: string; after: string }>()
const fullText = ref(false)
const allContext = ref(false)
const diff = computed(() => diffLines(props.before, props.after))
const rows = computed(() => (allContext.value ? diff.value.rows : withContext(diff.value.rows)))
watch(
  () => [props.file, props.before, props.after],
  () => {
    fullText.value = false
    allContext.value = false
  },
)
</script>

<template>
  <div class="line-diff">
    <p v-if="diff.limited" role="status">差异较大，已切换为全文对比；源码未截断。</p>
    <div v-else class="diff-controls">
      <button
        type="button"
        class="button secondary"
        :aria-pressed="fullText"
        @click="fullText = !fullText"
      >
        {{ fullText ? '逐行差异' : '全文对比' }}
      </button>
      <label v-if="!fullText"><input v-model="allContext" type="checkbox" /> 显示全部上下文</label>
    </div>
    <div v-if="fullText || diff.limited" class="comparison">
      <div>
        <h4>修改前</h4>
        <pre tabindex="0" :aria-label="`${file} 修改前`"><code>{{ before || '（空）' }}</code></pre>
      </div>
      <div>
        <h4>修改后</h4>
        <pre tabindex="0" :aria-label="`${file} 修改后`"><code>{{ after || '（空）' }}</code></pre>
      </div>
    </div>
    <div v-else class="diff-scroll" tabindex="0" role="region" :aria-label="`${file} 逐行差异`">
      <table>
        <caption>
          {{
            `${file} · 新增 ${diff.added} 行 · 删除 ${diff.removed} 行`
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">旧行</th>
            <th scope="col">新行</th>
            <th scope="col">变更</th>
            <th scope="col">代码</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="index" :data-kind="row.kind">
            <td v-if="row.kind === 'gap'" colspan="4" class="gap">
              … 省略 {{ row.count }} 行未修改内容
            </td>
            <template v-else>
              <td class="line-number">{{ row.oldLine }}</td>
              <td class="line-number">{{ row.newLine }}</td>
              <td
                class="change-mark"
                :aria-label="
                  row.kind === 'add' ? '新增' : row.kind === 'remove' ? '删除' : '未修改'
                "
              >
                {{ row.kind === 'add' ? '+' : row.kind === 'remove' ? '−' : '=' }}
              </td>
              <td>
                <code>{{ row.text || ' ' }}</code>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.line-diff {
  min-width: 0;
}
.diff-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
label {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 44px;
  cursor: pointer;
}
.comparison {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}
h4 {
  margin: 0.5rem 0;
}
pre,
.diff-scroll {
  overflow: auto;
  max-height: 24rem;
  border: 1px solid var(--border);
  font-size: 0.75rem;
}
pre {
  padding: 0.75rem;
}
pre:focus-visible,
.diff-scroll:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
table {
  border-collapse: collapse;
  width: 100%;
}
caption {
  text-align: left;
  padding: 0.75rem;
}
th,
td {
  text-align: left;
  vertical-align: top;
  padding: 0.25rem 0.5rem;
}
th,
.line-number,
.gap {
  color: var(--muted);
}
th {
  white-space: nowrap;
  border-bottom: 1px solid var(--border);
}
.line-number,
.change-mark {
  width: 1%;
  text-align: right;
  user-select: none;
}
code {
  white-space: pre;
}
[data-kind='add'] {
  background: color-mix(in srgb, var(--accent) 8%, var(--surface));
}
[data-kind='remove'] {
  background: color-mix(in srgb, var(--danger) 8%, var(--surface));
}
[data-kind='add'] .change-mark {
  color: var(--accent);
}
[data-kind='remove'] .change-mark {
  color: var(--danger);
}
.gap {
  text-align: center;
  padding: 0.5rem;
}
@media (max-width: 768px) {
  .comparison {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
