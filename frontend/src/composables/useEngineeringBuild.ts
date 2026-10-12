import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiFailure, errorMessage, isRecord, protocolFailure } from '../api/client'
import {
  buildActive,
  cancelBuild,
  getBuild,
  listBuildEvents,
  listBuilds,
  submitBuild,
  type BuildEvent,
  type EngineeringBuild,
} from '../api/builds'
import { randomId } from '../runtime/randomId'

export type BuildTarget = { ownerId: string; projectId: string; versionId: string }
type Intent = { kind: 'submit'; key: string } | { kind: 'cancel'; key: string; buildId: string }
type Request = { target: BuildTarget; controller: AbortController }
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const definiteRejections = new Set([
  'BUILD_DISABLED',
  'BUILD_ENVIRONMENT_NOT_READY',
  'BUILD_QUEUE_FULL',
  'BUILD_CAPACITY_EXCEEDED',
])
function storageKey(target: BuildTarget) {
  return `atoms.build.intent.v1:${target.ownerId}:${target.projectId}:${target.versionId}`
}
function readIntent(target: BuildTarget): Intent | null {
  try {
    const raw = sessionStorage.getItem(storageKey(target))
    if (raw === null) return null
    if (raw.length > 512) throw new Error()
    const value: unknown = JSON.parse(raw)
    if (!isRecord(value) || typeof value.key !== 'string' || !uuid.test(value.key))
      throw new Error()
    if (value.kind === 'submit' && Object.keys(value).length === 2)
      return { kind: 'submit', key: value.key }
    if (
      value.kind === 'cancel' &&
      Object.keys(value).length === 3 &&
      typeof value.buildId === 'string' &&
      uuid.test(value.buildId)
    )
      return { kind: 'cancel', key: value.key, buildId: value.buildId }
    throw new Error()
  } catch {
    throw new ApiFailure(
      'BUILD_INTENT_UNAVAILABLE',
      '无法读取本页的构建操作记录，请检查浏览器存储后重新加载。',
    )
  }
}
function saveIntent(target: BuildTarget, intent: Intent | null) {
  try {
    if (intent) sessionStorage.setItem(storageKey(target), JSON.stringify(intent))
    else sessionStorage.removeItem(storageKey(target))
  } catch {
    throw new ApiFailure(
      'BUILD_INTENT_UNAVAILABLE',
      '无法保存操作确认记录，请保留页面并检查浏览器存储。',
    )
  }
}

export function useEngineeringBuild(target: () => BuildTarget | null) {
  const build = ref<EngineeringBuild | null>(null)
  const history = ref<EngineeringBuild[]>([])
  const events = ref<BuildEvent[]>([])
  const pending = ref<Intent | null>(null)
  const busy = ref(false),
    loading = ref(false),
    loaded = ref(false),
    error = ref('')
  let request: Request | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let readRevision = 0
  const current = (value: Request) => request === value && !value.controller.signal.aborted
  const canSubmit = computed(
    () =>
      !!target() &&
      loaded.value &&
      !loading.value &&
      !busy.value &&
      !pending.value &&
      !error.value &&
      !buildActive(build.value),
  )
  const canCancel = computed(
    () =>
      !!target() &&
      !busy.value &&
      !loading.value &&
      !pending.value &&
      buildActive(build.value) &&
      build.value?.status !== 'CANCELLING',
  )
  function schedule() {
    clearTimeout(timer)
    if (
      request &&
      build.value &&
      buildActive(build.value) &&
      !busy.value &&
      !loading.value &&
      !error.value
    ) {
      const own = request,
        id = build.value.id
      timer = setTimeout(() => void observe(id, own), 2000)
    }
  }
  async function read(id: string, own: Request, revision: number) {
    const result = await getBuild(own.target.projectId, id, own.controller.signal)
    if (!current(own) || revision !== readRevision) return
    if (result.versionId !== own.target.versionId) return protocolFailure()
    if (build.value?.id !== id) events.value = []
    build.value = result
    history.value = [result, ...history.value.filter((item) => item.id !== result.id)].slice(0, 20)
    const after = events.value.at(-1)?.seq ?? 0
    const page = await listBuildEvents(own.target.projectId, id, after, own.controller.signal)
    if (current(own) && revision === readRevision)
      events.value = [...events.value, ...page.items].slice(-1000)
  }
  async function observe(id: string, own: Request) {
    const revision = ++readRevision
    try {
      await read(id, own, revision)
    } catch (failure) {
      if (current(own) && revision === readRevision) error.value = errorMessage(failure)
    } finally {
      if (current(own) && revision === readRevision) schedule()
    }
  }
  async function refresh() {
    if (!request || busy.value || loading.value) return
    const own = request,
      revision = ++readRevision
    clearTimeout(timer)
    loading.value = true
    error.value = ''
    try {
      pending.value = readIntent(own.target)
      const page = await listBuilds(own.target.projectId, own.controller.signal)
      if (!current(own) || revision !== readRevision) return
      history.value = page.items.filter((item) => item.versionId === own.target.versionId)
      const latest = history.value[0]
      if (latest) await read(latest.id, own, revision)
      else {
        build.value = null
        events.value = []
      }
      if (!current(own) || revision !== readRevision) return
      loaded.value = true
    } catch (failure) {
      if (current(own) && revision === readRevision) error.value = errorMessage(failure)
    } finally {
      if (current(own) && revision === readRevision) {
        loading.value = false
        schedule()
      }
    }
  }
  async function confirm() {
    if (!request || !pending.value || busy.value || loading.value) return
    const own = request,
      intent = pending.value
    let acceptedWrite = false
    clearTimeout(timer)
    busy.value = true
    error.value = ''
    // Invalidate a polling response before it can compete with the write's subsequent read.
    readRevision++
    try {
      const accepted =
        intent.kind === 'submit'
          ? await submitBuild(
              own.target.projectId,
              own.target.versionId,
              intent.key,
              own.controller.signal,
            )
          : await cancelBuild(
              own.target.projectId,
              intent.buildId,
              intent.key,
              own.controller.signal,
            )
      acceptedWrite = true
      if (!current(own)) return
      await read(accepted.buildId, own, ++readRevision)
      if (!current(own)) return
      saveIntent(own.target, null)
      pending.value = null
    } catch (failure) {
      if (!current(own)) return
      if (
        !acceptedWrite &&
        failure instanceof ApiFailure &&
        ((failure.status >= 400 && failure.status < 500) || definiteRejections.has(failure.code))
      ) {
        try {
          saveIntent(own.target, null)
          pending.value = null
        } catch {
          /* Retain the original intent when storage is unavailable. */
        }
      }
      error.value = errorMessage(failure)
    } finally {
      if (current(own)) {
        busy.value = false
        schedule()
      }
    }
  }
  async function begin(intent: Intent) {
    if (!request) return
    try {
      saveIntent(request.target, intent)
      pending.value = intent
      await confirm()
    } catch (failure) {
      error.value = errorMessage(failure)
    }
  }
  async function submit() {
    if (canSubmit.value) await begin({ kind: 'submit', key: randomId() })
  }
  async function cancel() {
    if (canCancel.value && build.value)
      await begin({ kind: 'cancel', key: randomId(), buildId: build.value.id })
  }
  function reset() {
    request?.controller.abort()
    request = null
    clearTimeout(timer)
    readRevision++
    build.value = null
    history.value = []
    events.value = []
    pending.value = null
    busy.value = false
    loading.value = false
    loaded.value = false
    error.value = ''
  }
  watch(
    () => JSON.stringify(target()),
    () => {
      reset()
      const selection = target()
      if (selection) {
        request = { target: { ...selection }, controller: new AbortController() }
        void refresh()
      }
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(reset)
  return {
    build,
    history,
    events,
    pending,
    busy,
    loading,
    error,
    canSubmit,
    canCancel,
    submit,
    cancel,
    confirm,
    refresh,
  }
}
