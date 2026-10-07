<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Project } from '../api/projects'
import { useSessionStore } from '../stores/session'
import { usePublication } from '../composables/usePublication'

const props = defineProps<{ project: Project }>()
const session = useSessionStore()
const {
  open,
  confirmed,
  busy,
  error,
  notice,
  publication,
  versionNo,
  report,
  pending,
  canPublish,
  refresh,
  submit,
  retry,
  takeOffline,
} = usePublication(
  () => props.project,
  () => session.ready,
)
const confirmOffline = ref(false)
const copyMessage = ref('')
const link = computed(() =>
  publication.value?.publicPath
    ? (publication.value.publicUrl ?? new URL(publication.value.publicPath, location.origin).href)
    : '',
)
async function copyLink() {
  try {
    await navigator.clipboard.writeText(link.value)
    copyMessage.value = '链接已复制。'
  } catch {
    copyMessage.value = '无法自动复制，请选中上方链接手动复制。'
  }
}
async function offline() {
  confirmOffline.value = false
  await takeOffline()
}
</script>

<template>
  <div class="publication-entry">
    <button
      class="button primary"
      type="button"
      :aria-expanded="open"
      aria-controls="publication-panel"
      :disabled="!session.ready"
      @click="open = !open"
    >
      发布应用 ↗
    </button>
    <section
      v-if="open"
      id="publication-panel"
      class="publication-panel"
      aria-labelledby="publication-title"
      :aria-busy="busy"
    >
      <div class="publication-heading">
        <div>
          <p class="eyebrow">PUBLISH A SNAPSHOT</p>
          <h2 id="publication-title">应用发布</h2>
        </div>
        <span class="outline-badge">{{
          publication?.status === 'PUBLISHED' ? '已发布' : publication?.slug ? '已下线' : '未发布'
        }}</span>
      </div>
      <p>只公开选定版本的应用。后续编辑不会自动更新已发布版本，私有对话和预览数据不会公开。</p>
      <p v-if="publication">
        当前版本 {{ versionNo ? `V${versionNo}` : '尚无版本' }} ·
        {{ publication.versionNo ? `上次发布 V${publication.versionNo}` : '尚未发布' }}
      </p>
      <p v-if="report?.status !== 'PASSED'" class="muted">
        发布前，请在当前版本的预览中保存一份通过的检查报告，再刷新发布状态。
      </p>
      <p v-else class="muted">当前版本已保存通过的预览检查报告。仍请检查页面内容是否适合公开。</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
      <div v-if="publication?.status === 'PUBLISHED' && link" class="publication-link">
        <label for="publication-url">{{
          publication.publicUrl ? '应用访问链接' : '本地访问链接（尚未接入公网）'
        }}</label>
        <input id="publication-url" :value="link" readonly />
        <div class="publication-actions">
          <a class="button secondary" :href="link" target="_blank" rel="noopener noreferrer"
            >打开应用 ↗</a
          >
          <button class="button secondary" type="button" @click="copyLink">复制链接</button>
        </div>
        <p v-if="copyMessage" role="status">{{ copyMessage }}</p>
      </div>
      <template v-if="pending">
        <p>
          上次{{
            pending.kind === 'publish' ? '发布' : '下线'
          }}结果未确认。重试将使用原版本、修订号和请求标识。
        </p>
        <button
          class="button primary"
          type="button"
          :disabled="busy || !session.ready"
          @click="retry"
        >
          重试原发布操作
        </button>
      </template>
      <template v-else>
        <label class="publication-consent"
          ><input v-model="confirmed" type="checkbox" :disabled="busy || !session.ready" />
          <span>我确认公开当前版本的页面、代码和初始数据（不包括私有预览数据）。</span></label
        >
        <div class="publication-actions">
          <button class="button primary" type="button" :disabled="!canPublish" @click="submit">
            {{ publication?.status === 'PUBLISHED' ? '更新已发布版本' : '确认发布当前版本' }}
          </button>
          <button
            class="button secondary"
            type="button"
            :disabled="busy || !session.ready"
            @click="refresh"
          >
            刷新发布状态
          </button>
          <button
            v-if="publication?.status === 'PUBLISHED'"
            class="button secondary"
            type="button"
            :disabled="busy || !session.ready || !!error"
            @click="confirmOffline = !confirmOffline"
          >
            下线应用
          </button>
        </div>
        <div
          v-if="confirmOffline && publication?.status === 'PUBLISHED'"
          class="publication-offline"
        >
          <p>下线后链接将不可访问，已保存的访客数据会保留。重新发布仍使用同一链接。</p>
          <button
            class="button secondary"
            type="button"
            :disabled="busy || !session.ready"
            @click="offline"
          >
            确认下线
          </button>
          <button class="button secondary" type="button" @click="confirmOffline = false">
            取消
          </button>
        </div>
      </template>
    </section>
  </div>
</template>
