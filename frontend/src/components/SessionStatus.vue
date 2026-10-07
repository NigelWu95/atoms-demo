<script setup lang="ts">
import { onMounted } from 'vue'
import { useSessionStore } from '../stores/session'
const session = useSessionStore()
onMounted(() => {
  void session.initialize()
})
</script>

<template>
  <div class="session-status" aria-live="polite">
    <p v-if="session.state === 'connecting'">正在连接访客会话…</p>
    <template v-else-if="session.state === 'failed'">
      <p role="alert">{{ session.error }}</p>
      <button class="button secondary" type="button" @click="session.initialize">
        重新连接会话
      </button>
    </template>
    <template v-else>
      <span>{{ session.identity?.displayName }}</span>
      <span class="muted">项目已启用持久化</span>
    </template>
  </div>
</template>
