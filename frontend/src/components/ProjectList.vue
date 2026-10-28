<script setup lang="ts">
import { ref, watch } from 'vue'
import { archiveProject, listProjects, type Project } from '../api/projects'
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
const search = ref('')
const query = ref('')
const sort = ref<'created' | 'updated'>('updated')
const scope = ref('active')
const changing = ref<string | null>(null)
const notice = ref('')
let revision = 0
async function load(more = false) {
  if (!session.ready || loading.value) return
  const request = ++revision
  loading.value = true
  error.value = ''
  try {
    const page = await listProjects(props.limit, more ? nextCursor.value : null, {
      q: query.value,
      sort: sort.value,
      archived: scope.value === 'archived',
    })
    if (request !== revision) return
    items.value = more
      ? [
          ...items.value,
          ...page.items.filter((item) => !items.value.some((existing) => existing.id === item.id)),
        ]
      : page.items
    nextCursor.value = page.nextCursor
    loaded.value = true
  } catch (failure) {
    if (request === revision) error.value = errorMessage(failure)
  } finally {
    if (request === revision) loading.value = false
  }
}
watch(
  () => [session.ready, session.identity?.userId, query.value, sort.value, scope.value],
  () => {
    revision++
    items.value = []
    loaded.value = false
    loading.value = false
    nextCursor.value = null
    error.value = ''
    changing.value = null
    notice.value = ''
    if (session.ready) void load()
  },
  { immediate: true },
)
async function archive(project: Project) {
  if (changing.value || !session.ready) return
  const request = revision
  const archived = scope.value !== 'archived'
  changing.value = project.id
  error.value = ''
  try {
    await archiveProject(project.id, archived)
    if (request !== revision) return
    changing.value = null
    notice.value = archived
      ? '已归档，可在“已归档”中恢复。发布链接不受影响。'
      : '项目已恢复到使用中列表。'
    await load()
  } catch (failure) {
    if (request === revision) error.value = errorMessage(failure)
  } finally {
    if (changing.value === project.id && request === revision) changing.value = null
  }
}
</script>

<template>
  <div class="project-list" :aria-busy="loading">
    <form v-if="!recent" class="project-filters" @submit.prevent="query = search.trim()">
      <label
        >搜索项目<input v-model="search" type="search" maxlength="80" placeholder="按项目名称搜索"
      /></label>
      <button type="submit" class="button secondary" :disabled="!session.ready">搜索</button>
      <label
        >排序<select v-model="sort" aria-label="项目排序">
          <option value="updated">最近编辑</option>
          <option value="created">最近创建</option>
        </select></label
      >
      <label
        >范围<select v-model="scope" aria-label="项目范围">
          <option value="active">使用中</option>
          <option value="archived">已归档</option>
        </select></label
      >
    </form>
    <p v-if="!session.ready" class="muted">连接会话后显示你的项目。</p>
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
        <h3>
          {{
            query
              ? '没有匹配的项目'
              : scope === 'archived'
                ? '还没有归档项目'
                : '还没有项目，从一个想法开始'
          }}
        </h3>
        <p v-if="query">试试其他名称，或清空搜索词后搜索。</p>
        <p v-else-if="scope === 'archived'">归档仅整理列表，不删除数据或关闭发布链接。</p>
        <p v-else>
          {{
            session.identity?.isGuest === false
              ? '创建后，项目会保存在当前账号下。'
              : '创建后，项目会保存在这个浏览器的访客空间里。'
          }}
        </p>
      </div>
    </div>
    <ul v-if="items.length" class="project-rows">
      <li v-for="project in items" :key="project.id">
        <RouterLink :to="`/projects/${project.id}`" class="project-row">
          <span class="project-mark" aria-hidden="true">◫</span>
          <span class="project-copy"
            ><strong>{{ project.name }}</strong
            ><span class="muted"
              >{{
                project.currentSourceVersionId
                  ? '工程已保存'
                  : project.currentVersionId
                    ? '已生成'
                    : '尚未生成'
              }}
              · 更新于 {{ new Date(project.updatedAt).toLocaleDateString('zh-CN') }}</span
            ></span
          >
          <span class="project-open">打开 <span aria-hidden="true">↗</span></span>
        </RouterLink>
        <button
          v-if="!recent"
          type="button"
          class="button secondary project-archive"
          :aria-label="`${scope === 'archived' ? '恢复' : '归档'} ${project.name}`"
          :disabled="!!changing || loading || !session.ready"
          @click="archive(project)"
        >
          {{ changing === project.id ? '处理中…' : scope === 'archived' ? '恢复' : '归档' }}
        </button>
      </li>
    </ul>
    <p v-if="notice" role="status">{{ notice }}</p>
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

<style scoped>
.project-filters {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.project-filters label {
  display: grid;
  gap: 0.375rem;
  font-size: 0.8125rem;
}
.project-filters input,
.project-filters select {
  min-height: 2.75rem;
  max-width: 100%;
  padding: 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  background: var(--surface, white);
  color: inherit;
  font: inherit;
}
.project-rows li {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.project-row {
  flex: 1;
  min-width: 0;
}
.project-archive {
  flex-shrink: 0;
  margin-right: 0.5rem;
}
@media (max-width: 480px) {
  .project-filters label:first-child {
    width: 100%;
  }
  .project-open {
    display: none;
  }
}
</style>
