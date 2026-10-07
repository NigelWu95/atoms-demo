<script setup lang="ts">
import { useRouter } from 'vue-router'
import PromptComposer from '../components/PromptComposer.vue'
import { useDraftStore } from '../stores/draft'

const router = useRouter()
const draft = useDraftStore()
const examples = [
  {
    name: '待办清单',
    mark: '✓',
    prompt: '做一个简洁的待办清单，支持添加、完成和筛选任务，使用清晰的卡片布局。',
  },
  {
    name: '活动报名表',
    mark: '▦',
    prompt: '做一个周末读书会报名页面，包含活动介绍、姓名和报名表单，以及报名成功提示。',
  },
  {
    name: '个人作品集',
    mark: '↗',
    prompt: '做一个设计师的个人作品集，有自我介绍、作品展示和联系区域，风格简洁有留白。',
  },
]
function continueToWorkbench(prompt: string) {
  draft.prompt = prompt
  void router.push('/workbench')
}
</script>

<template>
  <div class="home-page">
    <header class="page-topline">
      <span>你的下一个作品，从这里开始</span><span class="outline-badge">开发预览</span>
    </header>
    <section class="creation-space" aria-labelledby="home-title">
      <div class="hero-symbol" aria-hidden="true">✳</div>
      <p class="eyebrow">A LITTLE IDEA. A REAL POSSIBILITY.</p>
      <h1 id="home-title" tabindex="-1">让想法，<br /><span>有一个可以运行的形状。</span></h1>
      <p class="hero-description">
        描述你想做什么。在同一个工作台里，整理需求、生成应用、查看结果。
      </p>
      <PromptComposer v-model="draft.prompt" @submit="continueToWorkbench" />
      <div class="example-prompts" aria-label="示例需求">
        <span class="muted">试试这些想法</span
        ><button
          v-for="example in examples"
          :key="example.name"
          type="button"
          class="example-button"
          @click="draft.prompt = example.prompt"
        >
          <span aria-hidden="true">{{ example.mark }}</span
          >{{ example.name }}
        </button>
      </div>
      <p class="development-note">
        当前可整理需求并进入工作台；项目保存与 AI 生成将在后续阶段接入。
      </p>
    </section>
    <section class="recent-section" aria-labelledby="recent-title">
      <div class="section-heading">
        <h2 id="recent-title">你的创作空间</h2>
        <RouterLink to="/projects" class="subtle-link">查看项目 ↗</RouterLink>
      </div>
      <div class="empty-projects">
        <span class="empty-mark" aria-hidden="true">▤</span>
        <div>
          <h3>第一个作品，留给你的好想法</h3>
          <p>这里将展示你保存的项目。当前尚未连接项目管理功能。</p>
        </div>
        <span class="outline-badge">即将接入</span>
      </div>
    </section>
    <footer class="page-footer">
      <span>Atoms Demo Studio · 独立演示原型</span><span>构思 → 生成 → 预览 → 发布</span>
    </footer>
  </div>
</template>
