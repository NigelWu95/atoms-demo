<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PromptComposer from './PromptComposer.vue'
import { useDraftStore } from '../stores/draft'
import { useSessionStore } from '../stores/session'
import { createProject } from '../api/projects'
import { errorMessage } from '../api/client'

const router = useRouter(),
  route = useRoute(),
  draft = useDraftStore(),
  session = useSessionStore()
const creating = ref(false),
  error = ref('')
let mounted = true
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
async function create(prompt: string) {
  if (!session.ready || creating.value) return
  draft.prompt = prompt
  const intentKey = draft.creationKey(prompt),
    epoch = session.revision,
    origin = route.fullPath
  const current = () => mounted && epoch === session.revision && route.fullPath === origin
  creating.value = true
  error.value = ''
  try {
    const project = await createProject(prompt.replace(/\s+/g, ' ').slice(0, 40).trim(), intentKey)
    if (!current()) return
    draft.attach(project.id, prompt, intentKey)
    await router.push(`/projects/${project.id}`)
  } catch (failure) {
    if (current()) error.value = errorMessage(failure)
  } finally {
    if (current()) creating.value = false
  }
}
onBeforeUnmount(() => {
  mounted = false
})
</script>

<template>
  <PromptComposer
    v-model="draft.prompt"
    :disabled="!session.ready || creating"
    :action-label="creating ? '正在创建…' : '创建项目'"
    @submit="create"
  />
  <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
  <div class="example-prompts" aria-label="示例需求">
    <span class="muted">试试这些想法</span>
    <button
      v-for="example in examples"
      :key="example.name"
      type="button"
      class="example-button"
      :disabled="creating"
      @click="draft.prompt = example.prompt"
    >
      <span aria-hidden="true">{{ example.mark }}</span
      >{{ example.name }}
    </button>
  </div>
  <p class="welcome-note">
    创建项目不会调用模型，进入工作台后点击“生成应用”开始。未提交的需求草稿仅在当前页面会话中保留。
  </p>
</template>

<style scoped>
.example-prompts {
  justify-content: center;
}
.welcome-note {
  max-width: 600px;
  margin: 16px auto 0;
  font-size: 12px;
  color: var(--muted);
}
</style>
