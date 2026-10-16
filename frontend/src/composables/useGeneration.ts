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
import { subscribeToRun, type StreamState } from '../api/runEvents'
import { useSessionStore } from '../stores/session'
import { matchesRepair, repairPrompt, type RepairProposal } from '../runtime/previewRepair'

export function useGeneration(
  project: () => Project,
  prompt: () => string,
  setPrompt: (value: string) => void,
  changed: (value: Project) => void,
) {
  const intents = useGenerationStore()
  const session = useSessionStore()
  const run = ref<Run | null>(null)
  const messages = ref<Message[]>([])
  const cursor = ref<string | null>(null)
  const availability = ref<Availability | null>(null)
  const loading = ref(true)
  const busy = ref(false)
  const error = ref('')
  const loaded = ref(false)
  const connection = ref<StreamState>('idle')
  const pending = computed(() => intents.pending[project().id])
  const recoveryBlocked = computed(() => intents.isBlocked(project().id))
  const recoverySavedAt = computed(() => intents.savedAt[project().id])
  const storageUnavailable = computed(() => intents.storageUnavailable)
  const recoveryReady = computed(
    () =>
      loaded.value &&
      !loading.value &&
      session.ready &&
      session.identity?.userId === project().ownerId,
  )
  const active = computed(() => isActive(run.value))
  const canSubmit = computed(
    () =>
      loaded.value &&
      session.ready &&
      !loading.value &&
      !recoveryBlocked.value &&
      !busy.value &&
      !active.value &&
      !pending.value &&
      availability.value?.configured &&
      availability.value.workerEnabled &&
      availability.value.remainingRequests >= 2,
  )
  let revision = 0
  let mounted = true
  let stopStream: (() => void) | undefined
  const stopWatching = () => {
    stopStream?.()
    stopStream = undefined
    connection.value = 'idle'
  }
  let conversationRevision = 0
  function watchRun() {
    const task = run.value
    if (!task || !isActive(task)) return
    const request = revision
    const id = project().id
    const current = () =>
      mounted && revision === request && project().id === id && run.value?.id === task.id
    stopStream = subscribeToRun(task.id, task.lastEventSeq, {
      state: (state) => {
        if (current()) connection.value = state
      },
      error: (message) => {
        if (current()) error.value = message
      },
      event: (event) => {
        if (!current() || !run.value) return
        run.value.lastEventSeq = event.seq
        if (event.type === 'stage.started') {
          run.value.status = 'RUNNING'
          run.value.stage = event.stage
        }
        if (event.type === 'plan.ready') {
          const conversationRequest = ++conversationRevision
          void listMessages(id)
            .then((conversation) => {
              if (!current() || conversationRequest !== conversationRevision) return
              messages.value = [...conversation.items].reverse()
              cursor.value = conversation.nextCursor
            })
            .catch((failure: unknown) => {
              if (current()) error.value = errorMessage(failure)
            })
        }
        if (event.type === 'run.completed') void refresh()
      },
    })
  }

  async function refresh() {
    stopWatching()
    const request = ++revision
    const id = project().id
    const identityRevision = session.revision
    const currentView = () =>
      mounted &&
      request === revision &&
      project().id === id &&
      session.ready &&
      session.revision === identityRevision
    loading.value = true
    error.value = ''
    try {
      const history = await listRuns(id)
      if (!currentView()) return
      // A terminal run commits its version first. Read dependent data afterwards,
      // otherwise a parallel project read can leave a completed run on an old preview.
      const [settings, conversation, current] = await Promise.all([
        generationAvailability(),
        listMessages(id),
        getProject(id),
      ])
      if (!currentView()) return
      if (!session.identity || current.id !== id || current.ownerId !== session.identity.userId)
        throw new Error('项目归属校验失败，请重新进入工作台。')
      intents.bind(session.identity)
      if (!intents.activate(id, current.ownerId)) throw new Error('项目身份尚未验证，请刷新重试。')
      availability.value = settings
      run.value = history.items[0] ?? null
      messages.value = [...conversation.items].reverse()
      cursor.value = conversation.nextCursor
      loaded.value = true
      changed(current)
      if (
        run.value &&
        ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(run.value.status) &&
        intents.canRestoreDraft(id, run.value.id) &&
        !prompt().trim()
      )
        setPrompt(run.value.prompt)
      watchRun()
    } catch (failure) {
      if (currentView()) {
        error.value = errorMessage(failure)
        loaded.value = false
      }
    } finally {
      if (mounted && request === revision) loading.value = false
    }
  }
  async function submit() {
    if (
      busy.value ||
      !readyToWrite() ||
      recoveryBlocked.value ||
      (!canSubmit.value && !pending.value)
    )
      return
    const current = project()
    const id = current.id
    try {
      const intent = intents.intent(id, {
        prompt: prompt().trim(),
        intent: current.currentVersionId ? 'MODIFY' : 'CREATE',
        baseVersionId: current.currentVersionId ?? null,
        expectedProjectRevision: current.projectRevision,
      })
      await dispatch(current, intent)
    } catch {
      error.value = '恢复记录不可用或已达 10 个项目上限，请先核对并处理其他项目的恢复记录。'
    }
  }
  function readyToWrite() {
    return (
      loaded.value &&
      !loading.value &&
      session.ready &&
      session.identity?.userId === project().ownerId &&
      intents.authorized(project().id, project().ownerId)
    )
  }
  function discardRecovery() {
    if (busy.value || !readyToWrite()) return
    if (
      !window.confirm(
        '仅放弃本标签页的恢复记录，不会取消后台任务或删除任务历史。请先核对任务状态；再次提交新请求可能重复生成并消耗额度。若清除修复标记，该任务失败后可能回填普通输入框。确定继续？',
      )
    )
      return
    intents.discard(project().id)
  }
  async function submitRepair(proposal: RepairProposal, description: string): Promise<boolean> {
    if (!session.ready || !matchesRepair(proposal, project(), session.identity?.userId ?? null)) {
      error.value = '修复建议已失效，请在当前版本重新检查并准备。'
      return false
    }
    if (!readyToWrite() || !canSubmit.value || description.length > 1000) return false
    const current = project()
    try {
      const intent = intents.intent(
        current.id,
        {
          prompt: repairPrompt(proposal, description),
          intent: 'MODIFY',
          baseVersionId: proposal.baseVersionId,
          expectedProjectRevision: proposal.expectedProjectRevision,
        },
        true,
      )
      await dispatch(current, intent)
      return true
    } catch {
      error.value = '恢复记录不可用或已达 10 个项目上限，请先核对并处理其他项目的恢复记录。'
      return false
    }
  }
  async function dispatch(current: Project, intent: ReturnType<typeof intents.intent>) {
    const id = current.id
    const scope = session.revision
    const currentView = () =>
      mounted &&
      project().id === id &&
      project().ownerId === current.ownerId &&
      session.revision === scope
    busy.value = true
    error.value = ''
    stopWatching()
    revision++
    loading.value = false
    try {
      const result = await submitRun(id, intent.body, intent.key)
      if (
        session.revision !== scope ||
        intents.pending[id]?.key !== intent.key ||
        !intents.authorized(id, current.ownerId)
      )
        return
      intents.resolved(id, result.runId)
      if (!currentView()) return
      if (!intent.preserveDraft && prompt().trim() === intent.body.prompt) setPrompt('')
      await refresh()
    } catch (failure) {
      // Unknown outcomes retain the ORIGINAL request and key, including its baseline.
      if (
        session.revision === scope &&
        intents.pending[id]?.key === intent.key &&
        failure instanceof ApiFailure &&
        failure.status >= 400 &&
        failure.status < 500 &&
        intents.authorized(id, current.ownerId)
      )
        intents.resolved(id)
      if (currentView()) error.value = errorMessage(failure)
    } finally {
      if (currentView()) busy.value = false
    }
  }
  async function cancel() {
    if (!run.value || busy.value) return
    const id = project().id
    const taskId = run.value.id
    busy.value = true
    error.value = ''
    stopWatching()
    revision++
    loading.value = false
    try {
      await cancelRun(taskId)
      if (mounted && project().id === id) await refresh()
    } catch (failure) {
      if (mounted && project().id === id) {
        error.value = errorMessage(failure)
        watchRun()
      }
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
    stopWatching()
  })
  return {
    run,
    messages,
    cursor,
    availability,
    connection,
    loading,
    busy,
    error,
    pending,
    recoveryBlocked,
    recoverySavedAt,
    storageUnavailable,
    recoveryReady,
    discardRecovery,
    active,
    canSubmit,
    refresh,
    submit,
    submitRepair,
    cancel,
    older,
  }
}
