import { defineStore } from 'pinia'
import { onScopeDispose, ref } from 'vue'
import type { CreateRun } from '../api/runs'
import { randomId } from '../runtime/randomId'
import {
  clearRecovery,
  expireEntries,
  readRecovery,
  recoveryStamp,
  RECOVERY_TTL,
  sameRecoveryScope,
  writeRecovery,
  type PendingGeneration,
  type RecoveryEntry,
  type RecoveryScope,
} from '../runtime/generationRecovery'

// Cached requests are not authority: bind a verified identity, then authorize each project.
export const useGenerationStore = defineStore('generation', () => {
  const pending = ref<Record<string, PendingGeneration>>({})
  const repairRuns = ref<Record<string, string>>({})
  const expired = ref<Record<string, boolean>>({})
  const savedAt = ref<Record<string, number>>({})
  const blocked = ref(false)
  const storageUnavailable = ref(false)
  let scope: RecoveryScope | null = null
  let entries: RecoveryEntry[] = []
  const projects = new Set<string>()
  let timer: ReturnType<typeof setTimeout> | undefined
  function publish() {
    for (const id of projects) {
      const entry = entries.find((item) => item.projectId === id)
      delete pending.value[id]
      delete repairRuns.value[id]
      delete expired.value[id]
      delete savedAt.value[id]
      if (!entry) continue
      if (entry.pending) pending.value[id] = entry.pending
      if (entry.repairRunId) repairRuns.value[id] = entry.repairRunId
      if (entry.expired) expired.value[id] = true
      savedAt.value[id] = entry.createdAt
    }
  }
  function persist() {
    if (scope && !writeRecovery(scope, entries, blocked.value)) {
      storageUnavailable.value = true
      // Do not leave an older request body behind after resolution or expiry.
      clearRecovery()
    }
  }
  function schedule() {
    clearTimeout(timer)
    const deadlines = entries
      .filter((entry) => !entry.expired)
      .map((entry) => entry.createdAt + RECOVERY_TTL)
    if (deadlines.length)
      timer = setTimeout(prune, Math.max(0, Math.min(...deadlines) - Date.now()))
  }
  function prune() {
    const next = expireEntries(entries)
    if (next.length !== entries.length || next.some((entry, index) => entry !== entries[index])) {
      entries = next
      publish()
      persist()
    }
    schedule()
  }
  function bind(identity: { userId: string; isGuest: boolean }) {
    const stamp = recoveryStamp()
    const next = { ownerId: identity.userId, isGuest: identity.isGuest, stamp: stamp ?? '' }
    if (
      stamp === null &&
      scope?.ownerId === identity.userId &&
      scope.isGuest === identity.isGuest
    ) {
      storageUnavailable.value = true
      prune()
      return
    }
    if (scope && sameRecoveryScope(scope, next) && stamp !== null) {
      prune()
      return
    }
    clearTimeout(timer)
    pending.value = {}
    repairRuns.value = {}
    expired.value = {}
    savedAt.value = {}
    projects.clear()
    scope = next
    const cached =
      stamp === null ? { entries: [], blocked: true, unavailable: true } : readRecovery(next)
    if (stamp === null) clearRecovery()
    entries = cached.entries
    blocked.value = cached.blocked
    storageUnavailable.value = cached.unavailable
    schedule()
  }
  function activate(projectId: string, ownerId: string) {
    if (!scope || scope.ownerId !== ownerId) return false
    projects.add(projectId)
    prune()
    publish()
    return true
  }
  function authorized(projectId: string, ownerId: string) {
    if (!scope || scope.ownerId !== ownerId || !projects.has(projectId)) return false
    const stamp = recoveryStamp()
    if (stamp !== null && stamp !== scope.stamp) {
      reset()
      return false
    }
    // If storage is unavailable, only this live, already-verified page can continue.
    if (stamp === null) storageUnavailable.value = true
    prune()
    return true
  }
  function isBlocked(projectId: string) {
    return blocked.value || !!expired.value[projectId]
  }
  function intent(projectId: string, body: CreateRun, preserveDraft = false) {
    prune()
    if (isBlocked(projectId)) throw new Error('请先核对任务历史并处理恢复记录。')
    if (pending.value[projectId]) return pending.value[projectId]
    if (scope && !entries.some((entry) => entry.projectId === projectId) && entries.length >= 10)
      throw new Error('本标签页恢复记录已达 10 个项目上限，请先核对其他项目的待确认请求。')
    const request = { key: randomId(), body: Object.freeze({ ...body }), preserveDraft }
    pending.value[projectId] = request
    if (scope && projects.has(projectId)) {
      entries = [
        ...entries.filter((entry) => entry.projectId !== projectId),
        {
          projectId,
          createdAt: Date.now(),
          pending: request,
          repairRunId: repairRuns.value[projectId] ?? null,
          expired: false,
        },
      ]
      savedAt.value[projectId] = entries[entries.length - 1]!.createdAt
      persist()
      schedule()
    }
    return request
  }
  function resolved(projectId: string, runId?: string) {
    if (runId) {
      if (pending.value[projectId]?.preserveDraft) repairRuns.value[projectId] = runId
      else delete repairRuns.value[projectId]
    }
    delete pending.value[projectId]
    const entry = entries.find((item) => item.projectId === projectId)
    entries = entries.filter((item) => item.projectId !== projectId)
    if (entry && repairRuns.value[projectId])
      entries.push({ ...entry, pending: null, repairRunId: repairRuns.value[projectId] })
    publish()
    persist()
    schedule()
  }
  function canRestoreDraft(projectId: string, runId: string) {
    return (
      !isBlocked(projectId) &&
      !pending.value[projectId]?.preserveDraft &&
      repairRuns.value[projectId] !== runId
    )
  }
  function discard(projectId: string) {
    entries = entries.filter((entry) => entry.projectId !== projectId)
    blocked.value = false
    delete pending.value[projectId]
    delete repairRuns.value[projectId]
    delete expired.value[projectId]
    delete savedAt.value[projectId]
    persist()
    schedule()
  }
  function reset() {
    clearTimeout(timer)
    scope = null
    entries = []
    projects.clear()
    pending.value = {}
    repairRuns.value = {}
    expired.value = {}
    savedAt.value = {}
    blocked.value = false
    storageUnavailable.value = !clearRecovery()
  }
  onScopeDispose(() => clearTimeout(timer))
  return {
    pending,
    savedAt,
    storageUnavailable,
    bind,
    activate,
    authorized,
    isBlocked,
    discard,
    intent,
    resolved,
    canRestoreDraft,
    reset,
  }
})
