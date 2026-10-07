<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { renameProject, type Project } from '../api/projects'
import { errorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ project: Project }>()
const emit = defineEmits<{ renamed: [project: Project] }>()
const session = useSessionStore()
const editing = ref(false)
const name = ref('')
const saving = ref(false)
const error = ref('')
const input = ref<HTMLInputElement | null>(null)
const editButton = ref<HTMLButtonElement | null>(null)
watch(
  () => props.project.id,
  () => {
    editing.value = false
    error.value = ''
  },
)
async function edit() {
  name.value = props.project.name
  error.value = ''
  editing.value = true
  await nextTick()
  input.value?.focus()
  input.value?.select()
}
async function close() {
  editing.value = false
  await nextTick()
  editButton.value?.focus()
}
async function save() {
  if (saving.value || !session.ready || !name.value.trim() || name.value.length > 80) return
  saving.value = true
  error.value = ''
  const id = props.project.id
  try {
    const project = await renameProject(id, name.value.trim())
    if (props.project.id !== id) return
    emit('renamed', project)
    await close()
  } catch (failure) {
    if (props.project.id === id) error.value = errorMessage(failure)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="project-heading">
    <h1 tabindex="-1">{{ project.name }}</h1>
    <form v-if="editing" class="rename-form" @submit.prevent="save">
      <label for="project-name">项目名称</label>
      <input
        id="project-name"
        ref="input"
        v-model="name"
        maxlength="80"
        :disabled="saving"
        @keydown.esc.prevent="close"
      />
      <div class="rename-actions">
        <button
          class="button primary"
          type="submit"
          :disabled="saving || !session.ready || !name.trim()"
        >
          {{ saving ? '正在保存…' : '保存名称' }}
        </button>
        <button class="button secondary" type="button" :disabled="saving" @click="close">
          取消
        </button>
      </div>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    </form>
    <div v-else class="project-heading-meta">
      <span class="muted" role="status">项目名称已保存</span>
      <button
        ref="editButton"
        class="text-button"
        type="button"
        :disabled="!session.ready"
        @click="edit"
      >
        重命名
      </button>
    </div>
  </div>
</template>
