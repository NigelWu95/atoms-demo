<script setup lang="ts">
import { nextTick, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import ServiceStatus from './components/ServiceStatus.vue'
import SessionStatus from './components/SessionStatus.vue'
import EntryHeader from './components/EntryHeader.vue'
import { useSessionStore } from './stores/session'

const route = useRoute()
const session = useSessionStore()
watch(
  () => route.path,
  async () => {
    await nextTick()
    document.querySelector<HTMLElement>('h1')?.focus({ preventScroll: true })
  },
)
</script>

<template>
  <a href="#main-content" class="skip-link">跳到主要内容</a>
  <div
    v-if="route.matched.length && !route.meta.publicApp"
    class="app-shell"
    :class="{
      'entry-shell': route.meta.entryPage,
      'workbench-shell': route.path === '/workbench' || !!route.params.id,
    }"
  >
    <EntryHeader v-if="route.meta.entryPage" />
    <aside v-else class="sidebar">
      <RouterLink class="brand" to="/" aria-label="Atoms Demo Studio 首页">
        <span class="brand-mark" aria-hidden="true">✳</span>
        <span>atoms<span class="brand-subtitle">DEMO STUDIO</span></span>
      </RouterLink>
      <div class="workspace-name">
        <span class="workspace-avatar" aria-hidden="true">W</span>个人工作空间<span
          class="local-label"
          >本地</span
        >
      </div>
      <nav class="primary-nav" aria-label="主要导航">
        <RouterLink to="/" exact-active-class="active"
          ><span aria-hidden="true">＋</span>开始创作</RouterLink
        >
        <RouterLink to="/projects" active-class="active"
          ><span aria-hidden="true">▤</span>我的项目</RouterLink
        >
        <RouterLink to="/workbench" active-class="active"
          ><span aria-hidden="true">◫</span>工作台</RouterLink
        >
      </nav>
      <div class="sidebar-note">
        <span class="eyebrow">LOCAL DEVELOPMENT</span>
        <p>每个应用，<br />从一个好想法开始。</p>
      </div>
      <div class="sidebar-footer">
        <ServiceStatus />
        <div class="profile">
          <span class="profile-avatar" aria-hidden="true">你</span>
          <SessionStatus />
        </div>
      </div>
    </aside>
    <main id="main-content" class="main-content">
      <RouterView
        v-if="!session.hasConnected || session.identity || route.path === '/account'"
        v-show="!session.hasConnected || session.ready || route.path === '/account'"
        :key="route.path === '/account' ? 'account' : session.revision"
      />
      <section
        v-if="session.hasConnected && !session.ready && route.path !== '/account'"
        class="simple-page"
        aria-live="polite"
      >
        <h1 tabindex="-1">
          {{ session.state === 'connecting' ? '正在验证会话' : '请重新连接账号' }}
        </h1>
        <p>账号状态更新时，私有项目内容已隐藏。</p>
        <p v-if="session.error" role="alert">{{ session.error }}</p>
      </section>
    </main>
    <footer v-if="route.meta.entryPage" class="entry-footer"><ServiceStatus /></footer>
  </div>
  <main v-else-if="route.meta.publicApp" id="main-content" class="public-app-shell">
    <RouterView />
  </main>
</template>

<style scoped>
.entry-shell {
  display: flex;
  flex-direction: column;
}
.entry-shell > .main-content {
  flex: 1;
}
.entry-footer {
  display: flex;
  justify-content: center;
  padding: 24px;
}
</style>
