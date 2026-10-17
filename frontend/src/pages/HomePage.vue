<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PromptComposer from '../components/PromptComposer.vue'
import { useDraftStore } from '../stores/draft'
import { useSessionStore } from '../stores/session'
import { createProject } from '../api/projects'
import { errorMessage } from '../api/client'

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
  <div class="welcome-page">
    <section class="welcome-hero" aria-labelledby="home-title">
      <p class="eyebrow"><span aria-hidden="true">✳</span> 从一句想法，到一个应用</p>
      <h1 id="home-title" tabindex="-1">你有想法。<br /><span>让它运行起来。</span></h1>
      <p class="welcome-description">
        用自然语言描述需求，与 AI 一起生成、迭代和预览。<br class="desktop-break" />
        你的下一个小游戏、实用工具或作品页面，从这里开始。
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
      <p class="welcome-note">
        创建项目不会调用模型，进入工作台后点击“生成应用”开始。未提交的需求草稿仅在当前页面会话中保留。
      </p>
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
