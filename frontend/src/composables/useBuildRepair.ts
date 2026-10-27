import { onBeforeUnmount, ref, watch } from 'vue'
import { read } from '../api/projectWorkbench'
import { decodeExecutionEvents } from '../api/projectWorkbenchTasks'
import { errorMessage } from '../api/client'
import {
  buildRepairProposal,
  type BuildRepairProposal,
  type BuildRepairSelection,
} from '../runtime/buildRepair'

export function useBuildRepair(target: () => BuildRepairSelection | null) {
  const proposal = ref<BuildRepairProposal | null>(null),
    loading = ref(false),
    error = ref('')
  let epoch = 0,
    request = new AbortController()
  function clear() {
    epoch++
    request.abort()
    request = new AbortController()
    proposal.value = null
    loading.value = false
    error.value = ''
  }
  async function prepare() {
    const current = target()
    if (!current || loading.value) return
    clear()
    const revision = epoch
    loading.value = true
    try {
      const page = await read(
        current.projectId,
        `/execution/BUILD/${current.buildId}/events?limit=100`,
        decodeExecutionEvents,
        request.signal,
      )
      if (revision === epoch)
        proposal.value = buildRepairProposal(current, page.items, page.hasMore)
    } catch (failure) {
      if (revision === epoch) error.value = errorMessage(failure)
    } finally {
      if (revision === epoch) loading.value = false
    }
  }
  watch(() => JSON.stringify(target()), clear, { flush: 'sync' })
  onBeforeUnmount(clear)
  return { proposal, loading, error, prepare, clear }
}
