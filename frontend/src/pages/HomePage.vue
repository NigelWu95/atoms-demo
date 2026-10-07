<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PromptComposer from '../components/PromptComposer.vue'
import { useDraftStore } from '../stores/draft'
import { useSessionStore } from '../stores/session'
import { createProject } from '../api/projects'
import { errorMessage } from '../api/client'
import ProjectList from '../components/ProjectList.vue'

const router = useRouter()
const draft = useDraftStore()
const session = useSessionStore()
const creating = ref(false)
const error = ref('')
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
async function continueToWorkbench(prompt: string) {
  if (!session.ready || creating.value) return
  draft.prompt = prompt
  const intentKey = draft.creationKey(prompt)
  creating.value = true
  error.value = ''
  try {
    const name = prompt.replace(/\s+/g, ' ').slice(0, 40).trim()
    const project = await createProject(name, intentKey)
    draft.attach(project.id, prompt, intentKey)
    await router.push(`/projects/${project.id}`)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    creating.value = false
  }
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
      <PromptComposer
        v-model="draft.prompt"
        :disabled="!session.ready || creating"
        :action-label="creating ? '正在创建…' : '创建项目'"
        @submit="continueToWorkbench"
      />
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
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
        创建项目会保存名称，不会调用模型。需求草稿暂存在当前页面会话，AI 生成稍后接入。
      </p>
    </section>
    <section class="recent-section" aria-labelledby="recent-title">
      <div class="section-heading">
        <h2 id="recent-title">你的创作空间</h2>
        <RouterLink to="/projects" class="subtle-link">查看项目 ↗</RouterLink>
      </div>
      <ProjectList :limit="3" recent />
    </section>
    <footer class="page-footer">
      <span>Atoms Demo Studio · 独立演示原型</span><span>构思 → 生成 → 预览 → 发布</span>
    </footer>
  </div>
</template>
