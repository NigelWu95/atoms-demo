<script setup lang="ts">
import SessionStatus from './SessionStatus.vue'
</script>

<template>
  <header class="entry-header">
    <RouterLink class="brand" to="/" aria-label="Atoms Demo Studio 首页">
      <span class="brand-mark" aria-hidden="true">✳</span>
      <span>atoms<span class="brand-subtitle">DEMO STUDIO</span></span>
    </RouterLink>
    <nav aria-label="欢迎页导航">
      <RouterLink class="projects-link" to="/projects">我的项目</RouterLink>
      <SessionStatus compact />
    </nav>
  </header>
</template>

<style scoped>
.entry-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px 32px;
}
.brand {
  padding: 0;
  min-height: 44px;
}
nav {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 24px;
}
.projects-link {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--muted);
}
.projects-link:hover {
  color: var(--text);
}
@media (max-width: 600px) {
  .entry-header {
    padding: 20px;
  }
  nav {
    gap: 16px;
  }
}
@media (max-width: 380px) {
  nav {
    width: 100%;
  }
}
</style>
