<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api/client'
import { accountReturnTarget } from '../runtime/accountReturn'
import { useSessionStore } from '../stores/session'
import AccountIdentity from './AccountIdentity.vue'
defineProps<{ compact?: boolean }>()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const loggingOut = ref(false)
const logoutError = ref('')
async function logout() {
  if (loggingOut.value || !session.ready || session.identity?.isGuest !== false) return
  const source = route.fullPath
  const destination = route.meta.entryPage ? '/' : '/create'
  loggingOut.value = true
  logoutError.value = ''
  try {
    if ((await session.logout()) && route.fullPath === source) await router.replace(destination)
  } catch (failure) {
    // Session failures already have a reconnectable error owned by the session store.
    if (session.state !== 'failed') logoutError.value = errorMessage(failure)
  } finally {
    loggingOut.value = false
  }
}
const accountLink = computed(() => {
  if (session.ready && session.identity?.isGuest === false) return { path: '/settings/account' }
  const target = accountReturnTarget(route.path === '/account' ? route.query.returnTo : route.path)
  return { path: '/account', query: target ? { returnTo: target.path } : {} }
})
let stop: (() => void) | undefined
onMounted(() => {
  stop = session.listen()
  void session.initialize()
})
onBeforeUnmount(() => stop?.())
</script>

<template>
  <div class="session-status" :class="{ compact }" aria-live="polite">
    <p v-if="session.state === 'connecting'">{{ loggingOut ? '正在退出…' : '正在连接会话…' }}</p>
    <template v-else-if="session.state === 'failed'">
      <p role="alert">{{ session.error }}</p>
      <button class="button secondary" type="button" @click="session.initialize">
        重新连接会话
      </button>
    </template>
    <template v-else>
      <AccountIdentity v-if="session.identity" :identity="session.identity" />
      <span v-if="!compact" class="muted">项目已启用持久化</span>
    </template>
    <RouterLink
      :to="accountLink"
      class="account-link"
      :class="{ button: compact, primary: compact }"
      >{{
        session.ready && session.identity && !session.identity.isGuest
          ? compact
            ? '我的账号'
            : '管理账号'
          : compact
            ? '注册 / 登录'
            : '邮箱注册 / 登录'
      }}</RouterLink
    >
    <button
      v-if="session.identity?.isGuest === false"
      class="button secondary logout-button"
      type="button"
      :disabled="loggingOut || !session.ready"
      @click="logout"
    >
      {{ loggingOut ? '正在退出…' : '退出登录' }}
    </button>
    <p v-if="logoutError && session.state !== 'failed'" role="alert">{{ logoutError }}</p>
  </div>
</template>

<style scoped>
.session-status {
  min-width: 0;
  max-width: 100%;
}
.account-link {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  color: var(--accent);
}
.logout-button {
  min-height: 44px;
  padding: 8px 12px;
  margin-inline-start: 8px;
}
.compact .logout-button {
  margin: 0;
}
.compact {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  max-width: 440px;
}
.compact p {
  overflow-wrap: anywhere;
}
.compact .account-link {
  min-height: 44px;
  margin-left: 0;
  color: var(--on-accent);
}
.compact :deep(.account-identity),
.compact :deep(.identity-name) {
  color: var(--muted);
  max-width: 200px;
}
@media (max-width: 767px) {
  .account-link {
    margin-left: 12px;
  }
  .compact :deep(.account-identity) {
    flex-basis: 100%;
    max-width: 100%;
    text-align: right;
  }
  .compact :deep(.account-identity summary) {
    justify-content: flex-end;
  }
}
</style>
