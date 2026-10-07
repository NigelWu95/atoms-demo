<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiFailure } from '../api/client'
import { getPublicState, savePublicState, type PublicApp } from '../api/publicApps'
import { mountFrame } from '../runtime/mountFrame'
import type { BridgeStatus } from '../runtime/bridge'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ app: PublicApp }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const session = useSessionStore()
const container = ref<HTMLDivElement | null>(null)
const status = ref<BridgeStatus>('connecting')
const message = ref('正在加载应用…')
let cleanup: (() => void) | undefined
onMounted(() => {
  if (!container.value) return
  const { slug, versionId } = props.app
  cleanup = mountFrame(
    container.value,
    `/api/public/apps/${encodeURIComponent(slug)}/document?${new URLSearchParams({ versionId })}`,
    (request, key) => {
      if (!session.ready)
        return Promise.reject(new ApiFailure('SESSION_REQUIRED', '请先重新连接访客会话。', 401))
      if (request.type === 'STATE_GET') return getPublicState(slug, versionId)
      return savePublicState(
        slug,
        versionId,
        request.payload.value,
        request.payload.expectedRevision,
        key,
      )
    },
    (next, text) => {
      status.value = next
      message.value = text
      emit('busy', next === 'saving')
    },
    '已发布应用',
  )
})
onBeforeUnmount(() => {
  cleanup?.()
  emit('busy', false)
})
</script>

<template>
  <div class="runtime-preview">
    <div ref="container" class="runtime-container" />
    <p
      class="runtime-feedback"
      :class="{ 'inline-error': status === 'error' || status === 'unsaved' }"
      role="status"
      aria-live="polite"
    >
      {{ message }}
    </p>
    <p v-if="status === 'unsaved' || status === 'error'" class="inline-error">
      请保留未保存输入。应用更新或下线后需要重新加载；重新加载会丢弃当前未保存内容。
    </p>
  </div>
</template>
