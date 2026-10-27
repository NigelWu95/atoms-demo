import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '../stores/session'
import { getProject, type Project } from '../api/projects'
import { ApiFailure, errorMessage } from '../api/client'
import * as source from '../api/projectWorkbench'
import * as tasks from '../api/projectWorkbenchTasks'
import { downloadSource, restoreSource } from '../api/projectDelivery'
import { useWorkbenchIntent } from './useWorkbenchIntent'
import { useSourceBrowser } from './useSourceBrowser'
import { useEngineeringPreview } from './useEngineeringPreview'

export function useEngineeringWorkbench(projectId: () => string) {
  const session = useSessionStore(),
    intent = useWorkbenchIntent()
  const project = ref<Project | null>(null),
    capabilities = ref<source.WorkbenchCapabilities | null>(null)
  const head = ref<source.SourceHead | null>(null),
    versions = ref<source.SourceVersion[]>([]),
    nextVersion = ref<number | null>(null)
  const selectedVersionId = ref(''),
    selectedBuildId = ref('')
  const agents = ref<tasks.AgentTask[]>([]),
    builds = ref<tasks.ExecutionTask[]>([]),
    preparations = ref<tasks.ExecutionTask[]>([]),
    runs = ref<tasks.ExecutionTask[]>([])
  const taskCursors = ref<Record<string, string | null>>({
    builds: null,
    preparations: null,
    runs: null,
  })
  const runtime = ref({ coordinatorReady: false, databaseBound: false })
  const operation = ref<source.SourceOperation | null>(null),
    migration = ref<tasks.Migration | null>(null),
    approval = ref<string | null>(null)
  const loading = ref(false),
    refreshing = ref(false),
    readingMigration = ref(false),
    downloading = ref(false),
    error = ref(''),
    notice = ref('')
  const events = ref<tasks.TaskEvent[]>([]),
    eventTarget = ref<{ kind: 'AGENT' | 'BUILD' | 'PREPARATION' | 'RUN'; id: string } | null>(null)
  const eventCursor = ref<string | number | null>(null),
    eventsMore = ref(false),
    eventsLoading = ref(false),
    eventsError = ref('')
  let epoch = 0,
    eventEpoch = 0,
    migrationEpoch = 0,
    scope = new AbortController(),
    timer: ReturnType<typeof setTimeout> | undefined
  const objectUrls = new Set<string>()
  const identity = computed(() =>
    session.ready && project.value && project.value.ownerId === session.identity?.userId
      ? { project: project.value.id, owner: project.value.ownerId }
      : null,
  )
  const selectedVersion = computed(
    () => versions.value.find((v) => v.id === selectedVersionId.value) ?? null,
  )
  const selectedBuild = computed(
    () => builds.value.find((b) => b.id === selectedBuildId.value) ?? null,
  )
  const serving = computed(() => runs.value.find((r) => r.status === 'READY') ?? null)
  const activeRun = computed(
    () => runs.value.find((r) => !r.finishedAt && r.status !== 'NEEDS_RECONCILIATION') ?? null,
  )
  const sourceBrowser = useSourceBrowser(() =>
    identity.value && selectedVersionId.value
      ? { ...identity.value, version: selectedVersionId.value }
      : null,
  )
  const preview = useEngineeringPreview(
    () => identity.value,
    () => runs.value,
  )
  const canEdit = computed(
    () =>
      !!(
        capabilities.value?.enabled &&
        !capabilities.value.archived &&
        capabilities.value.sourceReady &&
        head.value?.status === 'READY' &&
        !intent.pending.value &&
        !agents.value.some((t) => !tasks.terminal(t.status)) &&
        !preparations.value.some((t) => !tasks.terminal(t.status))
      ),
  )
  function capture() {
    const target = identity.value
    return target ? { ...target, epoch, signal: scope.signal } : null
  }
  function same(current: { epoch: number }) {
    return current.epoch === epoch && !!identity.value
  }
  function merge<T extends { id: string }>(fresh: T[], prior: T[]) {
    const ids = new Set(fresh.map((item) => item.id))
    return [...fresh, ...prior.filter((old) => !ids.has(old.id))]
  }
  function needsPoll() {
    return !!(
      (operation.value &&
        !['COMMITTED', 'FAILED', 'CANCELLED', 'NEEDS_RECONCILIATION'].includes(
          operation.value.status,
        )) ||
      agents.value.some((t) => !tasks.terminal(t.status)) ||
      [...builds.value, ...preparations.value, ...runs.value].some((t) => !tasks.terminal(t.status))
    )
  }
  function schedule() {
    clearTimeout(timer)
    if (identity.value && !error.value && !document.hidden && needsPoll())
      timer = setTimeout(() => {
        void refresh()
      }, 2500)
  }
  async function refresh() {
    const current = capture()
    if (!current || refreshing.value || !capabilities.value?.enabled) return
    clearTimeout(timer)
    refreshing.value = true
    error.value = ''
    try {
      const operations = await source.read(
        current.project,
        '/source/operations',
        (value) => source.array(value, source.decodeOperation, 10),
        current.signal,
      )
      if (!same(current)) return
      operation.value = operations[0] ?? null
      const [newHead, page, newBuilds, newPreparations, newRuns, availability, newAgents] =
        await Promise.all([
          source.read(current.project, '/source/head', source.decodeHead, current.signal),
          source.read(current.project, '/source/versions', source.decodeVersions, current.signal),
          source.read(current.project, '/builds', tasks.decodeTaskPage, current.signal),
          source.read(current.project, '/preparations', tasks.decodeTaskPage, current.signal),
          source.read(current.project, '/runs', tasks.decodeTaskPage, current.signal),
          source.read(current.project, '/runtime', tasks.decodeRuntime, current.signal),
          capabilities.value.agentEnabled
            ? source.read(current.project, '/agent/tasks', tasks.decodeAgentTasks, current.signal)
            : Promise.resolve([]),
        ])
      if (!same(current)) return
      const followedHead =
        !selectedVersionId.value || selectedVersionId.value === head.value?.sourceVersionId
      head.value = newHead
      if (versions.value.length <= page.items.length) nextVersion.value = page.nextBeforeVersion
      versions.value = merge(page.items, versions.value)
      if (followedHead) selectedVersionId.value = newHead.sourceVersionId ?? ''
      for (const [kind, list] of Object.entries({
        builds: newBuilds,
        preparations: newPreparations,
        runs: newRuns,
      }))
        if (
          { builds, preparations, runs }[kind as 'builds' | 'preparations' | 'runs'].value.length <=
          list.items.length
        )
          taskCursors.value[kind] = list.nextCursor
      builds.value = merge(newBuilds.items, builds.value)
      preparations.value = merge(newPreparations.items, preparations.value)
      runs.value = merge(newRuns.items, runs.value)
      runtime.value = availability
      agents.value = newAgents
      if (!selectedBuildId.value)
        selectedBuildId.value =
          newBuilds.items.find((b) => b.status === 'SUCCEEDED')?.id ?? newBuilds.items[0]?.id ?? ''
      if (eventTarget.value) await loadEvents(false)
    } catch (failure) {
      if (same(current)) error.value = errorMessage(failure)
    } finally {
      if (same(current)) {
        refreshing.value = false
        schedule()
      }
    }
  }
  async function load() {
    epoch++
    eventEpoch++
    migrationEpoch++
    scope.abort()
    scope = new AbortController()
    clearTimeout(timer)
    intent.clear()
    for (const url of objectUrls) URL.revokeObjectURL(url)
    objectUrls.clear()
    project.value = null
    capabilities.value = null
    head.value = null
    versions.value = []
    nextVersion.value = null
    selectedVersionId.value = ''
    selectedBuildId.value = ''
    agents.value = []
    builds.value = []
    preparations.value = []
    runs.value = []
    taskCursors.value = { builds: null, preparations: null, runs: null }
    operation.value = null
    migration.value = null
    approval.value = null
    events.value = []
    eventTarget.value = null
    eventCursor.value = null
    runtime.value = { coordinatorReady: false, databaseBound: false }
    eventsMore.value = false
    eventsLoading.value = false
    eventsError.value = ''
    readingMigration.value = false
    downloading.value = false
    refreshing.value = false
    error.value = ''
    notice.value = ''
    const revision = epoch,
      id = projectId()
    loading.value = session.ready
    if (!session.ready) return
    try {
      const [value, access] = await Promise.all([
        getProject(id),
        source.capabilities(id, scope.signal),
      ])
      if (revision !== epoch) return
      project.value = value
      capabilities.value = access
      await refresh()
    } catch (failure) {
      if (revision === epoch) error.value = errorMessage(failure)
    } finally {
      if (revision === epoch) loading.value = false
    }
  }
  async function command(
    label: string,
    path: string,
    body: unknown,
    completed?: (result: tasks.CommandResult) => void,
  ) {
    const current = capture()
    if (!current) return
    const input = structuredClone(body)
    await intent.run(label, async (key) => {
      const result = await source.write(
        current.project,
        path,
        input,
        key,
        tasks.decodeCommand,
        current.signal,
      )
      if (!same(current)) return
      completed?.(result)
      notice.value = `${label}已受理：${tasks.statusLabel(result.status)}`
      void refresh()
    })
  }
  async function initialize() {
    const current = capture()
    if (!current || !capabilities.value?.agentEnabled) return
    await intent.run('初始化工程', async (key) => {
      const result = await source.write(
        current.project,
        '/source/initialize',
        {},
        key,
        source.decodeOperation,
        current.signal,
      )
      if (same(current)) {
        operation.value = result
        notice.value = '正在初始化工程；完成后可提交开发需求。'
        void refresh()
      }
    })
  }
  function submit(prompt: string, accepted: () => void) {
    const base = head.value
    if (
      !canEdit.value ||
      !base?.sourceVersionId ||
      !capabilities.value?.agentEnabled ||
      !prompt.trim()
    )
      return
    return command(
      '开发需求',
      '/agent/tasks',
      { prompt, baseSourceVersionId: base.sourceVersionId, expectedSourceRevision: base.revision },
      accepted,
    )
  }
  function build() {
    if (selectedVersionId.value)
      return command('构建', '/builds', { sourceVersionId: selectedVersionId.value }, (result) => {
        selectedBuildId.value = result.id
      })
  }
  function prepare() {
    const base = head.value
    if (canEdit.value && base?.sourceVersionId)
      return command('准备依赖', '/preparations', {
        baseSourceVersionId: base.sourceVersionId,
        expectedSourceRevision: base.revision,
      })
  }
  async function inspectMigration() {
    const current = capture(),
      build = selectedBuildId.value,
      request = ++migrationEpoch
    if (!current || !build || readingMigration.value) return
    readingMigration.value = true
    migration.value = null
    approval.value = null
    error.value = ''
    try {
      const result = await source.read(
        current.project,
        `/builds/${build}/migration`,
        tasks.decodeMigration,
        current.signal,
      )
      if (same(current) && request === migrationEpoch && selectedBuildId.value === build)
        migration.value = result
    } catch (failure) {
      if (same(current) && request === migrationEpoch) error.value = errorMessage(failure)
    } finally {
      if (same(current) && request === migrationEpoch) readingMigration.value = false
    }
  }
  async function approveMigration() {
    const current = capture(),
      review = migration.value
    if (!current || !review) return
    await intent.run('批准迁移', async (key) => {
      const id = await source.write(
        current.project,
        `/builds/${review.buildId}/migration-approvals`,
        { historySha256: review.historySha256, targetSha256: review.targetSha256 },
        key,
        tasks.decodeApproval,
        current.signal,
      )
      if (same(current) && migration.value === review) {
        approval.value = id
        notice.value = '已批准所展示构建与数据库历史对应的迁移。'
      }
    })
  }
  async function start() {
    const current = capture(),
      review = migration.value
    if (
      !current ||
      !review ||
      review.buildId !== selectedBuildId.value ||
      (review.decision === 'REVIEW_REQUIRED' && !approval.value)
    )
      return
    const input = {
      buildId: review.buildId,
      bindingId: review.bindingId,
      approvalId: approval.value,
      predecessorId: activeRun.value?.id ?? null,
    }
    await intent.run(input.predecessorId ? '替换运行' : '启动应用', async (key) => {
      const run = await source.write(
        current.project,
        '/runs',
        input,
        key,
        tasks.decodeExecution,
        current.signal,
      )
      if (!same(current)) return
      runs.value = merge([run], runs.value)
      void refresh()
      if (input.predecessorId === run.id)
        throw new ApiFailure(
          'RUN_REPLACEMENT_WAIT',
          '正在停止原运行。停止完成后点击“重试原请求”，继续启动选中的构建。',
          409,
        )
      notice.value = '启动请求已受理，等待应用就绪后打开预览。'
    })
  }
  async function restore() {
    const current = capture(),
      base = head.value,
      target = selectedVersionId.value
    if (!current || !base?.sourceVersionId || !target || target === base.sourceVersionId) return
    await intent.run('恢复源码', async (key) => {
      const result = await restoreSource(
        current.project,
        target,
        base.sourceVersionId!,
        base.revision,
        key,
        current.signal,
      )
      if (same(current)) {
        operation.value = result
        selectedVersionId.value = base.sourceVersionId!
        notice.value = '源码恢复已受理，不改变运行或业务数据。'
        void refresh()
      }
    })
  }
  async function moreVersions() {
    const current = capture(),
      before = nextVersion.value
    if (!current || !before) return
    try {
      const page = await source.read(
        current.project,
        `/source/versions?before=${before}`,
        source.decodeVersions,
        current.signal,
      )
      if (same(current)) {
        versions.value = merge(versions.value, page.items)
        nextVersion.value = page.nextBeforeVersion
      }
    } catch (failure) {
      if (same(current)) error.value = errorMessage(failure)
    }
  }
  async function moreTasks(kind: 'builds' | 'preparations' | 'runs') {
    const current = capture(),
      cursor = taskCursors.value[kind]
    if (!current || !cursor) return
    try {
      const page = await source.read(
        current.project,
        `/${kind}?${new URLSearchParams({ cursor })}`,
        tasks.decodeTaskPage,
        current.signal,
      )
      if (same(current)) {
        const list = { builds, preparations, runs }[kind]
        list.value = merge(list.value, page.items)
        taskCursors.value[kind] = page.nextCursor
      }
    } catch (failure) {
      if (same(current)) error.value = errorMessage(failure)
    }
  }
  async function showEvents(kind: 'AGENT' | 'BUILD' | 'PREPARATION' | 'RUN', id: string) {
    eventEpoch++
    eventTarget.value = { kind, id }
    events.value = []
    eventCursor.value = null
    eventsMore.value = false
    eventsLoading.value = false
    eventsError.value = ''
    await loadEvents(false)
  }
  async function loadEvents(explicit = true) {
    const current = capture(),
      target = eventTarget.value,
      request = eventEpoch
    if (!current || !target || eventsLoading.value || (!explicit && eventsError.value)) return
    eventsLoading.value = true
    eventsError.value = ''
    try {
      const result =
        target.kind === 'AGENT'
          ? await source.read(
              current.project,
              `/agent/tasks/${target.id}/events?after=${eventCursor.value ?? 0}`,
              tasks.decodeAgentEvents,
              current.signal,
            )
          : await source.read(
              current.project,
              `/execution/${target.kind}/${target.id}/events${eventCursor.value ? `?${new URLSearchParams({ cursor: String(eventCursor.value) })}` : ''}`,
              tasks.decodeExecutionEvents,
              current.signal,
            )
      if (!same(current) || request !== eventEpoch) return
      events.value = [
        ...events.value,
        ...result.items.filter((e) => !events.value.some((old) => old.sequence === e.sequence)),
      ].slice(-1000)
      eventCursor.value = result.nextCursor
      eventsMore.value = result.hasMore
    } catch (failure) {
      if (same(current) && request === eventEpoch) eventsError.value = errorMessage(failure)
    } finally {
      if (same(current) && request === eventEpoch) eventsLoading.value = false
    }
  }
  async function download(kind: 'builds' | 'previews', id: string) {
    const current = capture()
    if (!current || downloading.value) return
    downloading.value = true
    error.value = ''
    try {
      const blob = await downloadSource(current.project, kind, id, current.signal)
      if (!same(current)) return
      const url = URL.createObjectURL(blob)
      objectUrls.add(url)
      const link = document.createElement('a')
      link.href = url
      link.download = `atoms-${kind}-${id}-source.zip`
      link.click()
      setTimeout(() => {
        URL.revokeObjectURL(url)
        objectUrls.delete(url)
      }, 1000)
    } catch (failure) {
      if (same(current)) error.value = errorMessage(failure)
    } finally {
      if (same(current)) downloading.value = false
    }
  }
  watch(
    selectedBuildId,
    () => {
      migrationEpoch++
      readingMigration.value = false
      migration.value = null
      approval.value = null
    },
    { flush: 'sync' },
  )
  watch(
    () => [projectId(), session.ready, session.identity?.userId],
    () => {
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  function visibility() {
    clearTimeout(timer)
    if (!document.hidden && !error.value) void refresh()
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => {
    epoch++
    scope.abort()
    clearTimeout(timer)
    intent.clear()
    document.removeEventListener('visibilitychange', visibility)
    for (const url of objectUrls) URL.revokeObjectURL(url)
  })
  return {
    project,
    capabilities,
    head,
    versions,
    nextVersion,
    selectedVersionId,
    selectedVersion,
    selectedBuildId,
    selectedBuild,
    agents,
    builds,
    preparations,
    runs,
    taskCursors,
    runtime,
    operation,
    migration,
    approval,
    loading,
    refreshing,
    readingMigration,
    downloading,
    error,
    notice,
    serving,
    activeRun,
    canEdit,
    intent,
    sourceBrowser,
    preview,
    events,
    eventTarget,
    eventsMore,
    eventsLoading,
    eventsError,
    load,
    refresh,
    initialize,
    submit,
    command,
    build,
    prepare,
    inspectMigration,
    approveMigration,
    start,
    restore,
    moreVersions,
    moreTasks,
    showEvents,
    loadEvents,
    download,
  }
}
