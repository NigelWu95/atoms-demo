<script setup lang="ts">
import ProjectList from '../components/ProjectList.vue'
import { useSessionStore } from '../stores/session'
const session = useSessionStore()
</script>

<template>
  <section class="simple-page">
    <p class="eyebrow">YOUR WORKSPACE</p>
    <div class="section-heading">
      <h1 tabindex="-1">我的项目</h1>
      <RouterLink to="/" class="button primary">新建项目 ＋</RouterLink>
    </div>
    <p class="project-privacy-note">
      <template v-if="session.identity?.isGuest !== false"
        >默认按最近编辑排序。访客身份仅绑定此浏览器，清除 Cookie
        或会话过期后无法找回旧项目；可通过邮箱注册保存账号，请勿存放敏感资料。</template
      >
      <template v-else
        >默认按最近编辑排序。项目归属当前账号，可通过邮箱验证码在其他浏览器登录后继续创作。</template
      >
    </p>
    <p class="muted">归档可随时恢复，不删除代码或数据，也不会停用已发布链接。</p>
    <ProjectList />
  </section>
</template>
