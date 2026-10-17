<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getAccount, type AccountProfile } from '../api/account'
import { errorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'
import PasswordSettings from '../components/PasswordSettings.vue'

const session = useSessionStore(),
  router = useRouter()
const profile = ref<AccountProfile | null>(null),
  loading = ref(false),
  error = ref(''),
  busy = ref(false)
let request = 0,
  controller: AbortController | undefined
async function load() {
  const ticket = ++request,
    revision = session.revision
  controller?.abort()
  controller = new AbortController()
  profile.value = null
  if (!session.ready) return
  loading.value = true
  error.value = ''
  try {
    const value = await getAccount(controller.signal)
    if (ticket === request && revision === session.revision) profile.value = value
  } catch (failure) {
    if (ticket === request && revision === session.revision) error.value = errorMessage(failure)
  } finally {
    if (ticket === request) loading.value = false
  }
}
async function logout() {
  if (busy.value) return
  busy.value = true
  try {
    if (await session.logout()) await router.replace('/create')
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
watch(
  [() => session.revision, () => session.identity?.userId, () => session.identity?.isGuest],
  () => void load(),
  { immediate: true },
)
watch(
  () => session.ready,
  (ready) => {
    if (ready && !profile.value && !loading.value) void load()
  },
)
onBeforeUnmount(() => {
  request++
  controller?.abort()
})
</script>

<template>
  <section class="simple-page account-settings">
    <p class="eyebrow">YOUR WORKSPACE</p>
    <h1 tabindex="-1">账号管理</h1>
    <p class="muted">查看你的账号资料，管理登录方式。</p>
    <p v-if="loading" role="status">正在读取账号资料…</p>
    <div v-if="profile" class="account-card">
      <h2>{{ profile.displayName }}</h2>
      <span class="account-badge">{{
        profile.isGuest ? '访客 · 尚未注册' : '邮箱账号 · 已验证'
      }}</span>
      <dl class="account-facts">
        <template v-if="!profile.isGuest">
          <dt>邮箱地址</dt>
          <dd>{{ profile.email }}</dd>
        </template>
        <dt>{{ profile.isGuest ? '访客 ID' : '账号 ID' }}</dt>
        <dd>{{ profile.userId }}</dd>
        <template v-if="profile.createdAt">
          <dt>注册时间（本地时间）</dt>
          <dd>
            <time :datetime="profile.createdAt">{{
              new Date(profile.createdAt).toLocaleString()
            }}</time>
          </dd>
        </template>
      </dl>
      <RouterLink
        v-if="profile.isGuest"
        class="button primary"
        to="/account?returnTo=/settings/account"
        >注册 / 登录</RouterLink
      >
      <button
        v-else
        class="button secondary"
        type="button"
        :disabled="busy || !session.ready"
        @click="logout"
      >
        {{ busy ? '正在退出…' : '退出账号' }}
      </button>
    </div>
    <PasswordSettings
      v-if="profile && !profile.isGuest && profile.email"
      :key="profile.userId"
      :email="profile.email"
    />
    <p v-if="error" class="account-error" role="alert">{{ error }}</p>
    <button
      v-if="error"
      class="button secondary"
      type="button"
      :disabled="loading || !session.ready"
      @click="load"
    >
      重新读取账号资料
    </button>
  </section>
</template>

<style scoped src="../account.css"></style>
<style scoped>
.account-settings {
  max-width: 960px;
}
.account-settings .account-card {
  margin-top: 24px;
}
.account-facts {
  display: grid;
  grid-template-columns: minmax(120px, auto) 1fr;
  gap: 16px 24px;
  margin: 24px 0;
}
.account-facts dt {
  color: var(--muted);
}
.account-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
  min-width: 0;
}
@media (max-width: 767px) {
  .account-facts {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .account-facts dd {
    margin-bottom: 12px;
  }
}
</style>
