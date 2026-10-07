<script setup lang="ts">
import ProjectList from '../components/ProjectList.vue'
</script>

<template>
  <section class="simple-page">
    <p class="eyebrow">YOUR WORKSPACE</p>
    <div class="section-heading">
      <h1 tabindex="-1">我的项目</h1>
      <RouterLink to="/" class="button primary">新建项目 ＋</RouterLink>
    </div>
    <p class="project-privacy-note">
      按创建时间排序。访客身份仅绑定此浏览器，清除 Cookie
      或会话过期后无法找回旧项目；请勿存放敏感资料。
    </p>
    <ProjectList />
  </section>
</template>
