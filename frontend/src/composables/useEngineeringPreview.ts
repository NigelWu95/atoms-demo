import { onBeforeUnmount, ref, watch } from 'vue'
import { errorMessage, protocolFailure } from '../api/client'
import { openPreview, revokePreview, type DeliveryPreview } from '../api/projectDelivery'
import type { ExecutionTask } from '../api/projectWorkbenchTasks'

export function useEngineeringPreview(
  target: () => { project: string; owner: string } | null,
  runs: () => ExecutionTask[],
) {
  const preview = ref<DeliveryPreview | null>(null),
    opening = ref(false),
    error = ref('')
  let epoch = 0,
    request = new AbortController(),
    expiry: ReturnType<typeof setTimeout> | undefined
  let previewProject: string | null = null
  function clear(revoke = true) {
    epoch++
    request.abort()
    request = new AbortController()
    clearTimeout(expiry)
    const previous = preview.value,
      project = previewProject
    preview.value = null
    previewProject = null
    opening.value = false
    if (revoke && previous && project)
      void revokePreview(project, previous.previewId).catch(() => {
        /* Server TTL/session revocation still applies. */
      })
  }
  async function open(run: string) {
    const current = target()
    if (!current || opening.value) return
    clear()
    const revision = epoch
    opening.value = true
    error.value = ''
    try {
      const value = await openPreview(current.project, run, request.signal)
      if (revision !== epoch) return
      if (value.identity.projectId !== current.project || value.runId !== run)
        return protocolFailure()
      const remaining = Date.parse(value.expiresAt) - Date.now()
      if (remaining <= 0 || remaining > 10 * 60 * 1000 + 5000) return protocolFailure()
      previewProject = current.project
      preview.value = value
      expiry = setTimeout(() => {
        clear()
        error.value = '预览授权已过期，应用仍在运行。请重新打开预览。'
      }, remaining)
    } catch (failure) {
      if (revision === epoch) error.value = errorMessage(failure)
    } finally {
      if (revision === epoch) opening.value = false
    }
  }
  watch(
    () => {
      const t = target()
      return t ? `${t.owner}:${t.project}` : null
    },
    () => {
      clear()
      error.value = ''
    },
    { flush: 'sync' },
  )
  watch(
    () =>
      runs()
        .map((run) => `${run.id}:${run.status}`)
        .join('|'),
    () => {
      if (
        preview.value &&
        !runs().some((run) => run.id === preview.value?.runId && run.status === 'READY')
      ) {
        clear()
        error.value = '该运行已停止或状态变化，请重新检查运行状态。'
      }
    },
  )
  const visibility = () => {
    if (preview.value && Date.now() >= Date.parse(preview.value.expiresAt)) {
      clear()
      error.value = '预览授权已过期，请重新打开。'
    }
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => {
    clear()
    document.removeEventListener('visibilitychange', visibility)
  })
  return { preview, opening, error, open, clear }
}
