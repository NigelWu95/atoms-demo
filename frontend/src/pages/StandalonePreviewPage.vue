<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { getProject } from '../api/projects'
import { getVersion, type Version } from '../api/artifacts'
import { errorMessage } from '../api/client'
import PreviewFrame from '../components/PreviewFrame.vue'
import type { PreviewMode } from '../runtime/previewContext'
const route = useRoute()
const session = useSessionStore()
const id = computed(() => String(route.params.id))
const version = ref<Version | null>(null)
const mode = ref<PreviewMode>('HISTORY')
const error = ref('')
watch(
  () => [id.value, route.params.versionId, session.ready, session.identity?.userId],
  async (_, __, onCleanup) => {
    let active = true
    onCleanup(() => {
      active = false
    })
    version.value = null
    error.value = ''
    if (!session.ready) return
    try {
      const project = await getProject(id.value)
      const result = await getVersion(id.value, String(route.params.versionId))
      if (!active) return
      mode.value = project.currentVersionId === result.id ? 'CURRENT' : 'HISTORY'
      version.value = result
    } catch (failure) {
      if (active) error.value = errorMessage(failure)
    }
  },
  { immediate: true },
)
</script>

<template>
  <section class="simple-page standalone-preview">
    <RouterLink :to="`/projects/${id}`" class="subtle-link">← 返回项目</RouterLink>
    <h1 tabindex="-1">{{ version?.artifact.manifest.title ?? '私有应用预览' }}</h1>
    <p class="muted">此链接不是公开发布地址，仅当前项目的访客身份可以访问。</p>
    <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <PreviewFrame v-else-if="version" :key="version.id + mode" :version="version" :mode="mode" />
    <p v-else role="status">{{ session.ready ? '正在读取应用…' : '正在连接访客会话…' }}</p>
  </section>
</template>
