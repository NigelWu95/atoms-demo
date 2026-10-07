import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiFailure, errorMessage } from '../api/client'
import { getProject, type Project } from '../api/projects'
import {
  cancelRun,
  generationAvailability,
  isActive,
  listMessages,
  listRuns,
  submitRun,
  type Availability,
  type Message,
  type Run,
} from '../api/runs'
import { useGenerationStore } from '../stores/generation'

export function useGeneration(
  project: () => Project,
  prompt: () => string,
  setPrompt: (value: string) => void,
  changed: (value: Project) => void,
) {
  const intents = useGenerationStore()
  const run = ref<Run | null>(null)
  const messages = ref<Message[]>([])
  const cursor = ref<string | null>(null)
  const availability = ref<Availability | null>(null)
  const loading = ref(true)
  const busy = ref(false)
  const error = ref('')
  const loaded = ref(false)
  const pending = computed(() => intents.pending[project().id])
  const active = computed(() => isActive(run.value))
  const canSubmit = computed(
    () =>
      loaded.value &&
      !busy.value &&
      !active.value &&
      !pending.value &&
      availability.value?.configured &&
      availability.value.workerEnabled &&
      availability.value.remainingRequests >= 2,
  )
  let revision = 0
  let mounted = true
  let timer: ReturnType<typeof setTimeout> | undefined
  const clearTimer = () => {
    if (timer !== undefined) clearTimeout(timer)
  }

  async function refresh() {
    clearTimer()
    const request = ++revision
    const id = project().id
    loading.value = true
    error.value = ''
    try {
      const history = await listRuns(id)
      if (!mounted || request !== revision) return
      // A terminal run commits its version first. Read dependent data afterwards,
      // otherwise a parallel project read can leave a completed run on an old preview.
      const [settings, conversation, current] = await Promise.all([
        generationAvailability(),
        listMessages(id),
        getProject(id),
      ])
      if (!mounted || request !== revision) return
      availability.value = settings
      run.value = history.items[0] ?? null
      messages.value = [...conversation.items].reverse()
      cursor.value = conversation.nextCursor
      loaded.value = true
      changed(current)
      if (
        run.value &&
        ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(run.value.status) &&
        !prompt().trim()
      )
        setPrompt(run.value.prompt)
      if (isActive(run.value))
        timer = setTimeout(() => {
          void refresh()
        }, 1500)
    } catch (failure) {
      if (mounted && request === revision) {
        error.value = errorMessage(failure)
        loaded.value = false
      }
    } finally {
      if (mounted && request === revision) loading.value = false
    }
  }
  async function submit() {
    if (busy.value || (!canSubmit.value && !pending.value)) return
    const current = project()
    const id = current.id
    const intent = intents.intent(id, {
      prompt: prompt().trim(),
      intent: current.currentVersionId ? 'MODIFY' : 'CREATE',
      baseVersionId: current.currentVersionId ?? null,
      expectedProjectRevision: current.projectRevision,
    })
    busy.value = true
    error.value = ''
    clearTimer()
    revision++
    loading.value = false
    try {
      await submitRun(id, intent.body, intent.key)
      intents.resolved(id)
      if (!mounted || project().id !== id) return
      if (prompt().trim() === intent.body.prompt) setPrompt('')
      await refresh()
    } catch (failure) {
      // Unknown outcomes retain the ORIGINAL request and key, including its baseline.
      if (failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500)
        intents.resolved(id)
      if (mounted && project().id === id) error.value = errorMessage(failure)
    } finally {
      if (mounted && project().id === id) busy.value = false
    }
  }
  async function cancel() {
    if (!run.value || busy.value) return
    const id = project().id
    const taskId = run.value.id
    busy.value = true
    error.value = ''
    clearTimer()
    revision++
    loading.value = false
    try {
      await cancelRun(taskId)
      if (mounted && project().id === id) await refresh()
    } catch (failure) {
      if (mounted && project().id === id) error.value = errorMessage(failure)
    } finally {
      if (mounted && project().id === id) busy.value = false
    }
  }
  async function older() {
    if (!cursor.value || loading.value) return
    const request = revision
    const id = project().id
    loading.value = true
    try {
      const page = await listMessages(id, cursor.value)
      if (!mounted || request !== revision) return
      const known = new Set(messages.value.map((message) => message.id))
      messages.value = [
        ...page.items.filter((message) => !known.has(message.id)).reverse(),
        ...messages.value,
      ]
      cursor.value = page.nextCursor
    } catch (failure) {
      if (mounted && request === revision) error.value = errorMessage(failure)
    } finally {
      if (mounted && request === revision) loading.value = false
    }
  }
  watch(
    () => project().id,
    () => {
      run.value = null
      messages.value = []
      loaded.value = false
      void refresh()
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    mounted = false
    revision++
    clearTimer()
  })
  return {
    run,
    messages,
    cursor,
    availability,
    loading,
    busy,
    error,
    pending,
    active,
    canSubmit,
    refresh,
    submit,
    cancel,
    older,
  }
}
