import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { readDraft, saveDraft, type DraftWrite } from '../api/drafts'
import { ApiFailure, errorMessage } from '../api/client'

export function useProjectDraft(
  target: () => { id: string; ownerId: string } | null,
  prompt: Ref<string>,
  hasLocal: () => boolean,
) {
  const status = ref<'idle' | 'loading' | 'saved' | 'dirty' | 'saving' | 'error' | 'conflict'>(
    'idle',
  )
  const error = ref('')
  const pausedDirty = ref(false)
  const dirty = computed(
    () =>
      pausedDirty.value ||
      ['dirty', 'saving', 'error', 'conflict'].includes(status.value) ||
      (status.value === 'loading' && !!prompt.value),
  )
  let epoch = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let revision = 0
  let saved = ''
  let pending: DraftWrite | null = null
  let applying = false
  let editedDuringLoad = false
  function clearTimer() {
    clearTimeout(timer)
    timer = undefined
  }
  function schedule() {
    clearTimer()
    if (!target() || ['idle', 'loading', 'error', 'conflict', 'saving'].includes(status.value))
      return
    status.value = prompt.value === saved ? 'saved' : 'dirty'
    if (status.value === 'dirty')
      timer = setTimeout(() => {
        void persist()
      }, 600)
  }
  async function load(discard = false) {
    clearTimer()
    const project = target()
    // Session revalidation temporarily hides the target, not its unsaved input.
    if (!project) pausedDirty.value = dirty.value && hasLocal()
    const request = ++epoch
    pending = null
    revision = 0
    saved = ''
    error.value = ''
    editedDuringLoad = false
    status.value = project ? 'loading' : 'idle'
    if (!project) return
    const local = hasLocal()
    try {
      const value = await readDraft(project.id)
      if (request !== epoch) return
      revision = value.revision
      saved = value.prompt
      pausedDirty.value = false
      // A previously cleared draft is still a remote edit. Reload only discards
      // the text present when clicked, never input typed while the GET is pending.
      if (((!discard && local) || editedDuringLoad) && prompt.value !== saved && revision > 0) {
        status.value = 'conflict'
        error.value = '服务器有另一份草稿，当前输入已保留。复制需要的内容后，可重新读取服务器草稿。'
        return
      }
      if (!editedDuringLoad && (discard || !local)) {
        applying = true
        prompt.value = saved
        applying = false
      }
      status.value = 'saved'
      schedule()
    } catch (failure) {
      if (request === epoch) {
        pausedDirty.value = false
        status.value = 'error'
        error.value = errorMessage(failure)
      }
    }
  }
  async function persist() {
    const project = target()
    if (!project || status.value === 'saving') return
    const request = epoch
    pending ??= { prompt: prompt.value, expectedRevision: revision }
    const write = pending
    status.value = 'saving'
    error.value = ''
    try {
      const value = await saveDraft(project.id, write)
      if (request !== epoch) return
      revision = value.revision
      saved = value.prompt
      pending = null
      status.value = 'saved'
      schedule()
    } catch (failure) {
      if (request !== epoch) return
      status.value =
        failure instanceof ApiFailure && failure.code === 'DRAFT_CONFLICT' ? 'conflict' : 'error'
      error.value = errorMessage(failure)
    }
  }
  async function retry() {
    if (status.value !== 'error') return
    if (pending) await persist()
    else await load()
  }
  async function reload() {
    // Unknown writes must be settled by exact retry before a read can replace local state.
    if (status.value !== 'conflict') return
    await load(true)
  }
  watch(
    () => {
      const value = target()
      return value ? `${value.ownerId}:${value.id}` : null
    },
    () => {
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  watch(
    prompt,
    () => {
      if (applying) return
      if (status.value === 'loading') editedDuringLoad = true
      schedule()
    },
    { flush: 'sync' },
  )
  watch(
    hasLocal,
    (exists) => {
      if (!exists) pausedDirty.value = false
    },
    { flush: 'sync' },
  )
  function warn(event: BeforeUnloadEvent) {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  window.addEventListener('beforeunload', warn)
  onBeforeUnmount(() => {
    epoch++
    clearTimer()
    window.removeEventListener('beforeunload', warn)
  })
  return { status, error, dirty, retry, reload }
}
