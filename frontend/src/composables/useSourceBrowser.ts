import { onBeforeUnmount, ref, watch } from 'vue'
import { errorMessage } from '../api/client'
import {
  decodeDiff,
  decodeFile,
  decodeTree,
  read,
  type SourceDiff,
  type SourceEntry,
  type SourceFile,
} from '../api/projectWorkbench'

export function useSourceBrowser(
  target: () => { project: string; owner: string; version: string } | null,
) {
  const tree = ref<SourceEntry[]>([]),
    file = ref<SourceFile | null>(null),
    diff = ref<SourceDiff | null>(null)
  const selectedPath = ref(''),
    loading = ref(false),
    error = ref(''),
    diffLoading = ref(false)
  let epoch = 0,
    fileEpoch = 0,
    diffEpoch = 0
  let scope = new AbortController(),
    fileRequest = new AbortController(),
    diffRequest = new AbortController()
  async function selectFile(path: string) {
    const current = target(),
      revision = epoch,
      request = ++fileEpoch
    fileRequest.abort()
    fileRequest = new AbortController()
    selectedPath.value = path
    file.value = null
    error.value = ''
    if (!current) return
    loading.value = true
    try {
      const result = await read(
        current.project,
        `/source/versions/${current.version}/file?${new URLSearchParams({ path })}`,
        decodeFile,
        AbortSignal.any([scope.signal, fileRequest.signal]),
      )
      if (revision === epoch && request === fileEpoch) file.value = result
    } catch (failure) {
      if (revision === epoch && request === fileEpoch) error.value = errorMessage(failure)
    } finally {
      if (revision === epoch && request === fileEpoch) loading.value = false
    }
  }
  async function compare(before: string) {
    const current = target(),
      revision = epoch,
      request = ++diffEpoch
    diffRequest.abort()
    diffRequest = new AbortController()
    diff.value = null
    error.value = ''
    if (!current) return
    diffLoading.value = true
    try {
      const result = await read(
        current.project,
        `/source/diff?${new URLSearchParams({ before, after: current.version })}`,
        decodeDiff,
        AbortSignal.any([scope.signal, diffRequest.signal]),
      )
      if (revision === epoch && request === diffEpoch) diff.value = result
    } catch (failure) {
      if (revision === epoch && request === diffEpoch) error.value = errorMessage(failure)
    } finally {
      if (revision === epoch && request === diffEpoch) diffLoading.value = false
    }
  }
  async function load() {
    const revision = ++epoch,
      current = target()
    scope.abort()
    scope = new AbortController()
    fileEpoch++
    diffEpoch++
    tree.value = []
    file.value = null
    diff.value = null
    selectedPath.value = ''
    error.value = ''
    diffLoading.value = false
    loading.value = !!current
    if (!current) return
    try {
      const entries = await read(
        current.project,
        `/source/versions/${current.version}/tree`,
        decodeTree,
        scope.signal,
      )
      if (revision !== epoch) return
      tree.value = entries
      const first =
        entries.find((entry) => entry.path === selectedPath.value) ??
        entries.find((entry) => entry.path === 'README.md') ??
        entries[0]
      if (first) await selectFile(first.path)
    } catch (failure) {
      if (revision === epoch) error.value = errorMessage(failure)
    } finally {
      if (revision === epoch) loading.value = false
    }
  }
  watch(
    () => {
      const t = target()
      return t ? `${t.owner}:${t.project}:${t.version}` : null
    },
    () => {
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    epoch++
    scope.abort()
    fileRequest.abort()
    diffRequest.abort()
  })
  return {
    tree,
    file,
    diff,
    selectedPath,
    loading,
    diffLoading,
    error,
    selectFile,
    compare,
    reload: load,
  }
}
