import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiFailure, errorMessage } from '../api/client'
import { getProject, type Project } from '../api/projects'
import { listVersions } from '../api/artifacts'
import { getPreviewReport, type SavedReport } from '../api/previewReports'
import { randomId } from '../runtime/randomId'
import {
  getPublication,
  publish,
  unpublish,
  type Publication,
  type PublishInput,
} from '../api/publication'

type Intent = { projectId: string; key: string } & (
  { kind: 'publish'; body: PublishInput } | { kind: 'unpublish'; revision: number }
)
export function usePublication(project: () => Project, ready: () => boolean) {
  const open = ref(false)
  const confirmed = ref(false)
  const busy = ref(false)
  const error = ref('')
  const notice = ref('')
  const publication = ref<Publication | null>(null)
  const current = ref<Project | null>(null)
  const versionNo = ref<number | null>(null)
  const report = ref<SavedReport | null>(null)
  const pending = ref<Intent | null>(null)
  let revision = 0
  let alive = true
  const canPublish = computed(
    () =>
      ready() &&
      !busy.value &&
      !pending.value &&
      !error.value &&
      confirmed.value &&
      !!current.value?.currentVersionId &&
      !!publication.value &&
      report.value?.status === 'PASSED' &&
      !(
        publication.value.status === 'PUBLISHED' &&
        publication.value.versionId === current.value.currentVersionId
      ),
  )
  async function load() {
    const request = ++revision
    const id = project().id
    error.value = ''
    confirmed.value = false
    try {
      const [fresh, live] = await Promise.all([getProject(id), getPublication(id)])
      const [versions, checks] = await Promise.all([
        listVersions(id),
        fresh.currentVersionId ? getPreviewReport(id, fresh.currentVersionId) : null,
      ])
      if (!alive || request !== revision) return
      current.value = fresh
      publication.value = live
      report.value = checks
      versionNo.value =
        versions.items.find((item) => item.id === fresh.currentVersionId)?.versionNo ?? null
    } catch (failure) {
      if (alive && request === revision) error.value = errorMessage(failure)
    }
  }
  async function refresh() {
    if (!ready() || busy.value || pending.value) return
    busy.value = true
    try {
      await load()
    } finally {
      if (alive) busy.value = false
    }
  }
  async function retry() {
    if (!ready() || busy.value || !pending.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    const intent = pending.value
    try {
      if (intent.kind === 'publish') await publish(intent.projectId, intent.body, intent.key)
      else await unpublish(intent.projectId, intent.revision, intent.key)
      if (!alive) return
      pending.value = null
      notice.value = '操作已确认；以下展示服务器当前发布状态。'
      // An idempotent replay can describe an older publication; always read the current pointer.
      await load()
    } catch (failure) {
      if (!alive) return
      if (failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500)
        pending.value = null
      error.value = errorMessage(failure)
      confirmed.value = false
    } finally {
      if (alive) busy.value = false
    }
  }
  async function submit() {
    if (!canPublish.value || !current.value?.currentVersionId || !publication.value) return
    pending.value = {
      kind: 'publish',
      projectId: project().id,
      key: randomId(),
      body: {
        versionId: current.value.currentVersionId,
        expectedProjectRevision: current.value.projectRevision,
        expectedPublicationRevision: publication.value.revision,
      },
    }
    await retry()
  }
  async function takeOffline() {
    if (
      !ready() ||
      busy.value ||
      pending.value ||
      error.value ||
      publication.value?.status !== 'PUBLISHED'
    )
      return
    pending.value = {
      kind: 'unpublish',
      projectId: project().id,
      key: randomId(),
      revision: publication.value.revision,
    }
    await retry()
  }
  watch(
    () => [open.value, project().currentVersionId, project().projectRevision, ready()],
    () => {
      if (open.value) void refresh()
    },
  )
  onBeforeUnmount(() => {
    alive = false
    revision++
  })
  return {
    open,
    confirmed,
    busy,
    error,
    notice,
    publication,
    current,
    versionNo,
    report,
    pending,
    canPublish,
    refresh,
    submit,
    retry,
    takeOffline,
  }
}
