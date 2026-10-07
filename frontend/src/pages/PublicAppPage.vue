<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getPublicApp, type PublicApp } from '../api/publicApps'
import { errorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'
import PublicFrame from '../components/PublicFrame.vue'

const route = useRoute()
const session = useSessionStore()
const slug = computed(() => String(route.params.slug ?? ''))
const app = ref<PublicApp | null>(null)
const loading = ref(false)
const saving = ref(false)
const connected = ref(false)
const error = ref('')
let revision = 0
async function load() {
  const current = ++revision
  loading.value = true
  app.value = null
  connected.value = false
  error.value = ''
  try {
    const result = await getPublicApp(slug.value)
    if (current !== revision) return
    app.value = result
    if (!session.ready) await session.initialize()
    if (current === revision) connected.value = session.ready
  } catch (failure) {
    if (current === revision) error.value = errorMessage(failure)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function reconnect() {
  await session.initialize()
  if (session.ready) connected.value = true
}
watch(
  slug,
  () => {
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revision++
})
</script>

<template>
  <div class="public-app-page" :aria-busy="loading">
    <header class="public-app-header">
      <div>
        <p class="eyebrow">PUBLISHED APP</p>
        <h1 tabindex="-1">{{ app?.title ?? (error ? '应用暂不可用' : '正在打开应用') }}</h1>
        <p v-if="app" class="muted">{{ app.summary }}</p>
      </div>
      <span v-if="app" class="outline-badge">已发布 · V{{ app.versionNo }}</span>
    </header>
    <section v-if="error" class="public-app-message">
      <p role="alert" class="inline-error">{{ error }}</p>
      <button class="button secondary" type="button" @click="load">重试打开应用</button>
    </section>
    <p v-if="loading" role="status">正在连接应用与访客数据…</p>
    <section v-if="app && session.state === 'failed'" class="public-app-message">
      <p role="alert" class="inline-error">{{ session.error }}</p>
      <button class="button secondary" type="button" @click="reconnect">重新连接会话</button>
    </section>
    <template v-if="app && connected">
      <div class="public-app-toolbar">
        <p class="muted">
          数据仅属于当前浏览器访客，清除 Cookie 后无法恢复身份。重新加载前请先保存输入。
        </p>
        <button class="button secondary" type="button" :disabled="saving || loading" @click="load">
          重新加载应用
        </button>
      </div>
      <PublicFrame
        :key="`${app.slug}:${app.versionId}:${session.identity?.userId}`"
        :app="app"
        @busy="saving = $event"
      />
    </template>
  </div>
</template>
