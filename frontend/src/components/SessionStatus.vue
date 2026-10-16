<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useSessionStore } from '../stores/session'
const session = useSessionStore()
let stop: (() => void) | undefined
onMounted(() => {
  stop = session.listen()
  void session.initialize()
})
onBeforeUnmount(() => stop?.())
</script>

<template>
  <div class="session-status" aria-live="polite">
    <p v-if="session.state === 'connecting'">正在连接会话…</p>
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
    <RouterLink to="/account" class="account-link">{{
      session.identity && !session.identity.isGuest ? '管理账号' : '邮箱注册 / 登录'
    }}</RouterLink>
  </div>
</template>

<style scoped>
.account-link {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  color: var(--accent);
}
@media (max-width: 767px) {
  .account-link {
    margin-left: 12px;
  }
}
</style>
