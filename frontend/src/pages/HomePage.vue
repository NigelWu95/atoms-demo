<script setup lang="ts">
import IdeaComposer from '../components/IdeaComposer.vue'
</script>

<template>
  <div class="welcome-page">
    <section class="welcome-hero" aria-labelledby="home-title">
      <p class="eyebrow"><span aria-hidden="true">✳</span> 从一句想法，到一个应用</p>
      <h1 id="home-title" tabindex="-1">你有想法。<br /><span>让它运行起来。</span></h1>
      <p class="welcome-description">
        用自然语言描述需求，与 AI 一起生成、迭代和预览。<br class="desktop-break" />
        你的下一个小游戏、实用工具或作品页面，从这里开始。
      </p>
      <IdeaComposer />
    </section>
    <section class="welcome-process" aria-labelledby="process-title">
      <h2 id="process-title">把想法，一步步变成作品</h2>
      <ol>
        <li>
          <span class="step-number">01</span>
          <div>
            <h3>说出想法</h3>
            <p>描述目标和使用场景，自动创建你的项目。</p>
          </div>
        </li>
        <li>
          <span class="step-number">02</span>
          <div>
            <h3>一起打磨</h3>
            <p>生成后直接预览，继续对话调整功能与细节。</p>
          </div>
        </li>
        <li>
          <span class="step-number">03</span>
          <div>
            <h3>带走作品</h3>
            <p>保留历史版本，发布链接或下载工程继续开发。</p>
          </div>
        </li>
      </ol>
    </section>
    <p class="welcome-footnote">Atoms Demo Studio · AI 应用创作原型</p>
  </div>
</template>

<style scoped src="../welcome.css"></style>
