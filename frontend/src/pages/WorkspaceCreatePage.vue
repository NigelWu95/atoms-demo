<script setup lang="ts">
import IdeaComposer from '../components/IdeaComposer.vue'
</script>
<template>
  <section class="simple-page workspace-create">
    <p class="eyebrow">LET'S CREATE</p>
    <h1 tabindex="-1">今天，想做点什么？</h1>
    <p class="muted">描述一个新想法，或从左侧继续已有项目。</p>
    <IdeaComposer />
  </section>
</template>
<style scoped>
.workspace-create {
  max-width: 920px;
  margin: 0 auto;
  padding-top: clamp(32px, 8vh, 96px);
}
.workspace-create :deep(.composer) {
  margin-top: 32px;
}
</style>
