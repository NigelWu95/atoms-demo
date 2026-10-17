<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useSessionStore } from '../stores/session'
defineProps<{ compact?: boolean }>()
const session = useSessionStore()
let stop: (() => void) | undefined
onMounted(() => {
  stop = session.listen()
  void session.initialize()
})
onBeforeUnmount(() => stop?.())
</script>

<template>
  <div class="session-status" :class="{ compact }" aria-live="polite">
    <p v-if="session.state === 'connecting'">正在连接会话…</p>
    <template v-else-if="session.state === 'failed'">
      <p role="alert">{{ session.error }}</p>
      <button class="button secondary" type="button" @click="session.initialize">
        重新连接会话
      </button>
    </template>
    <template v-else>
      <span class="identity-name">{{ session.identity?.displayName }}</span>
      <span v-if="!compact" class="muted">项目已启用持久化</span>
    </template>
    <RouterLink to="/account" class="account-link" :class="{ button: compact, primary: compact }">{{
      session.ready && session.identity && !session.identity.isGuest
        ? compact
          ? '我的账号'
          : '管理账号'
        : compact
          ? '注册 / 登录'
          : '邮箱注册 / 登录'
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
.compact {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  max-width: 360px;
}
.compact p {
  overflow-wrap: anywhere;
}
.compact .account-link {
  min-height: 44px;
  margin-left: 0;
  color: var(--on-accent);
}
.compact .identity-name {
  color: var(--muted);
  max-width: 144px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 767px) {
  .account-link {
    margin-left: 12px;
  }
  .compact .identity-name {
    display: none;
  }
}
</style>
