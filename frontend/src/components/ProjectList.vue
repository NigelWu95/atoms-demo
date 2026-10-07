<script setup lang="ts">
import { ref, watch } from 'vue'
import { listProjects, type Project } from '../api/projects'
import { errorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'

const props = withDefaults(defineProps<{ limit?: number; recent?: boolean }>(), {
  limit: 20,
  recent: false,
})
const session = useSessionStore()
const items = ref<Project[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
let revision = 0
async function load(more = false) {
  if (!session.ready || loading.value) return
  const request = ++revision
  loading.value = true
  error.value = ''
  try {
    const page = await listProjects(props.limit, more ? nextCursor.value : null)
    if (request !== revision) return
    items.value = more ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    loaded.value = true
  } catch (failure) {
    if (request === revision) error.value = errorMessage(failure)
  } finally {
    if (request === revision) loading.value = false
  }
}
watch(
  () => [session.ready, session.identity?.userId],
  () => {
    revision++
    items.value = []
    loaded.value = false
    loading.value = false
    nextCursor.value = null
    error.value = ''
    if (session.ready) void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="project-list" :aria-busy="loading">
    <p v-if="!session.ready" class="muted">连接访客会话后显示你的项目。</p>
    <p v-else-if="loading && !loaded" role="status" class="list-loading">正在读取你的项目…</p>
    <div v-if="error" class="inline-error" role="alert">
      <p>{{ error }}</p>
      <button
        class="button secondary"
        type="button"
        :disabled="loading || !session.ready"
        @click="load(items.length > 0)"
      >
        重试加载项目
      </button>
    </div>
    <div v-if="loaded && !items.length" class="empty-projects">
      <span class="empty-mark" aria-hidden="true">▤</span>
      <div>
        <h3>还没有项目，从一个想法开始</h3>
        <p>创建后，项目会保存在这个浏览器的访客空间里。</p>
      </div>
    </div>
    <ul v-if="items.length" class="project-rows">
      <li v-for="project in items" :key="project.id">
        <RouterLink :to="`/projects/${project.id}`" class="project-row">
          <span class="project-mark" aria-hidden="true">◫</span>
          <span class="project-copy"
            ><strong>{{ project.name }}</strong
            ><span class="muted"
              >尚未生成 · 更新于 {{ new Date(project.updatedAt).toLocaleDateString('zh-CN') }}</span
            ></span
          >
          <span class="project-open">打开 <span aria-hidden="true">↗</span></span>
        </RouterLink>
      </li>
    </ul>
    <button
      v-if="nextCursor && !recent"
      class="button secondary"
      type="button"
      :disabled="loading || !session.ready"
      @click="load(true)"
    >
      {{ loading ? '正在加载…' : '加载更多' }}
    </button>
  </div>
</template>
