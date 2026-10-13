import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiFailure, errorMessage, isRecord } from '../api/client'
import {
  instanceActive,
  listInstances,
  startInstance,
  stopInstance,
  type AppInstance,
} from '../api/instances'
import { randomId } from '../runtime/randomId'

type Target = { ownerId: string; projectId: string }
type Intent = { kind: 'start' | 'stop'; id: string; key: string }
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
function storageKey(target: Target) {
  return `atoms.instance.intent.v1:${target.ownerId}:${target.projectId}`
}
function readIntent(target: Target): Intent | null {
  const raw = sessionStorage.getItem(storageKey(target))
  if (raw === null) return null
  if (raw.length > 512) throw new Error('Invalid intent')
  const value: unknown = JSON.parse(raw)
  if (
    !isRecord(value) ||
    Object.keys(value).length !== 3 ||
    !['start', 'stop'].includes(String(value.kind)) ||
    typeof value.id !== 'string' ||
    !uuid.test(value.id) ||
    typeof value.key !== 'string' ||
    !uuid.test(value.key)
  )
    throw new Error('Invalid intent')
  return value as Intent
}
export function useAppInstance(target: () => Target | null) {
  const instance = ref<AppInstance | null>(null),
    pending = ref<Intent | null>(null)
  const busy = ref(false),
    loading = ref(false),
    loaded = ref(false),
    error = ref('')
  let current: { target: Target; controller: AbortController } | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let revision = 0
  const canStart = computed(
    () =>
      !!current &&
      loaded.value &&
      !loading.value &&
      !busy.value &&
      !pending.value &&
      !error.value &&
      (!instance.value || !instanceActive(instance.value)),
  )
  const canStop = computed(
    () =>
      !!current &&
      !busy.value &&
      !loading.value &&
      !pending.value &&
      !!instance.value &&
      instanceActive(instance.value) &&
      instance.value.status !== 'STOPPING',
  )
  function schedule() {
    clearTimeout(timer)
    if (
      current &&
      instance.value &&
      instanceActive(instance.value) &&
      !busy.value &&
      !loading.value &&
      !error.value
    )
      timer = setTimeout(() => void refresh(), 2000)
  }
  async function refresh() {
    if (!current || busy.value || loading.value) return
    const own = current,
      read = ++revision
    clearTimeout(timer)
    loading.value = true
    error.value = ''
    try {
      pending.value = readIntent(own.target)
      const items = await listInstances(own.target.projectId, own.controller.signal)
      if (current !== own || read !== revision) return
      instance.value = items.find(instanceActive) ?? items[0] ?? null
      loaded.value = true
    } catch (failure) {
      if (current === own && read === revision) error.value = errorMessage(failure)
    } finally {
      if (current === own && read === revision) {
        loading.value = false
        schedule()
      }
    }
  }
  async function perform(initial: boolean) {
    if (!current || !pending.value || busy.value || loading.value) return
    const own = current,
      intent = pending.value
    clearTimeout(timer)
    revision++
    busy.value = true
    error.value = ''
    let accepted = false
    try {
      if (intent.kind === 'start')
        await startInstance(own.target.projectId, intent.id, intent.key, own.controller.signal)
      else await stopInstance(own.target.projectId, intent.id, intent.key, own.controller.signal)
      accepted = true
      if (current !== own) return
      sessionStorage.removeItem(storageKey(own.target))
      pending.value = null
    } catch (failure) {
      if (current !== own) return
      if (
        initial &&
        !accepted &&
        failure instanceof ApiFailure &&
        ((failure.status >= 400 && failure.status < 500) ||
          [
            'RUNTIME_DISABLED',
            'RUNTIME_ENVIRONMENT_NOT_READY',
            'RUNTIME_CAPACITY_EXCEEDED',
            'RUNTIME_DATABASE_POOL_FULL',
          ].includes(failure.code))
      ) {
        try {
          sessionStorage.removeItem(storageKey(own.target))
          pending.value = null
        } catch {
          /* Keep the original intent if persistence fails. */
        }
      }
      error.value = errorMessage(failure)
    } finally {
      if (current === own) {
        busy.value = false
        if (accepted && !pending.value) await refresh()
        else schedule()
      }
    }
  }
  async function begin(kind: Intent['kind'], id: string) {
    if (!current || !uuid.test(id)) return
    const intent: Intent = { kind, id, key: randomId() }
    try {
      sessionStorage.setItem(storageKey(current.target), JSON.stringify(intent))
      pending.value = intent
    } catch {
      error.value = '无法保存操作记录，请检查浏览器存储后重试。'
      return
    }
    await perform(true)
  }
  function start(buildId: string) {
    if (canStart.value) return begin('start', buildId)
  }
  function stop() {
    if (canStop.value && instance.value) return begin('stop', instance.value.id)
  }
  function dispose() {
    clearTimeout(timer)
    current?.controller.abort()
    current = null
    revision++
  }
  watch(
    () => [target()?.ownerId, target()?.projectId],
    () => {
      dispose()
      instance.value = null
      pending.value = null
      busy.value = false
      loading.value = false
      loaded.value = false
      error.value = ''
      const value = target()
      if (value) {
        current = { target: { ...value }, controller: new AbortController() }
        void refresh()
      }
    },
    { immediate: true },
  )
  onBeforeUnmount(dispose)
  return {
    instance,
    pending,
    busy,
    loading,
    error,
    canStart,
    canStop,
    start,
    stop,
    refresh,
    confirm: () => perform(false),
  }
}
