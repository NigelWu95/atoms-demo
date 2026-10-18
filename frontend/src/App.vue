<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import ServiceStatus from './components/ServiceStatus.vue'
import SessionStatus from './components/SessionStatus.vue'
import EntryHeader from './components/EntryHeader.vue'
import { useSessionStore } from './stores/session'

const route = useRoute()
const session = useSessionStore()
const isWorkbench = computed(() => route.meta.workbench === true)
const navigationOpen = ref(false)
const sidebarCollapsed = ref(false)
watch(
  () => route.path,
  async () => {
    navigationOpen.value = false
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
      'workbench-shell': isWorkbench,
      'sidebar-collapsed': sidebarCollapsed && !route.meta.entryPage,
    }"
  >
    <EntryHeader v-if="route.meta.entryPage" />
    <aside
      v-else
      id="workspace-sidebar"
      class="sidebar"
      :class="{ 'navigation-open': navigationOpen }"
    >
      <RouterLink class="brand" to="/" aria-label="Atoms Demo Studio 首页">
        <span class="brand-mark" aria-hidden="true">✳</span>
        <span class="brand-name">atoms<span class="brand-subtitle">DEMO STUDIO</span></span>
      </RouterLink>
      <button
        type="button"
        class="button secondary sidebar-toggle"
        data-action="toggle-sidebar"
        :aria-expanded="!sidebarCollapsed"
        :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        aria-controls="workspace-sidebar"
        @click="sidebarCollapsed = !sidebarCollapsed"
      >
        <span aria-hidden="true">{{ sidebarCollapsed ? '»' : '«' }}</span>
        <span class="sidebar-label">收起侧栏</span>
      </button>
      <button
        v-if="isWorkbench"
        type="button"
        class="button secondary workspace-navigation-toggle"
        :aria-expanded="navigationOpen"
        aria-controls="workspace-navigation workspace-account"
        @click="navigationOpen = !navigationOpen"
      >
        {{ navigationOpen ? '收起导航' : '导航与账号' }}
      </button>
      <div class="workspace-name">
        <span class="workspace-avatar" aria-hidden="true">W</span>个人工作空间<span
          class="local-label"
          >本地</span
        >
      </div>
      <nav id="workspace-navigation" class="primary-nav" aria-label="主要导航">
        <RouterLink to="/create" exact-active-class="active" aria-label="开始创作" title="开始创作"
          ><span aria-hidden="true">＋</span><span class="sidebar-label">开始创作</span></RouterLink
        >
        <RouterLink to="/projects" active-class="active" aria-label="我的项目" title="我的项目"
          ><span aria-hidden="true">▤</span><span class="sidebar-label">我的项目</span></RouterLink
        >
        <RouterLink to="/workbench" active-class="active" aria-label="工作台" title="工作台"
          ><span aria-hidden="true">◫</span><span class="sidebar-label">工作台</span></RouterLink
        >
      </nav>
      <div class="sidebar-note">
        <span class="eyebrow">LOCAL DEVELOPMENT</span>
        <p>每个应用，<br />从一个好想法开始。</p>
      </div>
      <div id="workspace-account" class="sidebar-footer">
        <button
          type="button"
          class="button secondary sidebar-account-toggle"
          aria-label="展开侧栏查看账号"
          title="查看账号"
          @click="sidebarCollapsed = false"
        >
          你
        </button>
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
        v-show="
          (route.path !== '/' && !session.hasConnected) ||
          session.ready ||
          route.path === '/account'
        "
        :key="route.path === '/account' ? 'account' : session.revision"
      />
      <section
        v-if="
          (session.hasConnected || route.path === '/') &&
          !session.ready &&
          route.path !== '/account'
        "
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
