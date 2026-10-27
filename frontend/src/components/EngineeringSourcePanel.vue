<script setup lang="ts">
import type { UnwrapNestedRefs } from 'vue'
import type { useSourceBrowser } from '../composables/useSourceBrowser'
defineProps<{ browser: UnwrapNestedRefs<ReturnType<typeof useSourceBrowser>>; diff: boolean }>()
</script>
<template>
  <section
    class="engineering-source"
    aria-label="工程源码"
    :aria-busy="browser.loading || browser.diffLoading"
  >
    <p v-if="browser.error" role="alert" class="inline-error">
      {{ browser.error }} <button type="button" @click="browser.reload">重新读取</button>
    </p>
    <template v-if="diff">
      <div class="engineering-scroll engineering-diff" tabindex="0" aria-label="源码差异">
        <p v-if="browser.diffLoading">正在读取差异…</p>
        <template v-else-if="browser.diff">
          <p>
            {{ browser.diff.changes.length }} 个文件变化<span v-if="browser.diff.truncated">
              · 差异已截断，请查看具体文件</span
            >
          </p>
          <ul>
            <li v-for="change in browser.diff.changes" :key="change.path">
              {{ change.kind }} · {{ change.path }}
            </li>
          </ul>
          <pre>{{ browser.diff.preview || '两个版本的文件相同。' }}</pre>
        </template>
        <p v-else class="muted">选择一个版本，与其来源版本比较。</p>
      </div>
    </template>
    <div v-else class="engineering-files">
      <nav class="engineering-file-tree engineering-scroll" aria-label="工程文件">
        <button
          v-for="entry in browser.tree"
          :key="entry.path"
          type="button"
          :aria-pressed="browser.selectedPath === entry.path"
          :title="entry.path"
          @click="browser.selectFile(entry.path)"
        >
          {{ entry.path }}
        </button>
        <p v-if="!browser.tree.length" class="muted">
          {{ browser.loading ? '读取文件中…' : '暂无工程文件' }}
        </p>
      </nav>
      <div class="engineering-code-pane">
        <div class="engineering-file-caption">
          {{ browser.selectedPath || '选择文件'
          }}<span v-if="browser.file"> · {{ browser.file.bytes.toLocaleString() }} 字节</span>
        </div>
        <p v-if="browser.file?.truncated" class="muted">仅展示前 256 KiB，工程下载保留完整文件。</p>
        <pre class="engineering-scroll" tabindex="0" aria-label="源码内容">{{
          browser.loading ? '读取中…' : (browser.file?.content ?? '')
        }}</pre>
      </div>
    </div>
  </section>
</template>
