import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { buildActive } from '../api/builds'
import { instanceActive } from '../api/instances'
import type { BuildTarget, useEngineeringBuild } from './useEngineeringBuild'
import type { useAppInstance } from './useAppInstance'

type Attempt = {
  key: string
  buildId: string | null
  phase: 'checking' | 'building' | 'launching' | 'starting'
}

// Only this page's explicit click grants permission for a later automatic start.
export function useRunApplication(
  target: () => BuildTarget | null,
  builds: ReturnType<typeof useEngineeringBuild>,
  runtime: ReturnType<typeof useAppInstance>,
  preview: () => void,
) {
  const attempt = ref<Attempt | null>(null)
  const message = ref('点击后将构建工程、启动本机进程并打开预览；不会调用模型或自动发布。')
  const key = () => JSON.stringify(target())
  const current = (own: Attempt) => attempt.value === own && own.key === key()
  const problem = () =>
    builds.error.value ||
    runtime.error.value ||
    (builds.pending.value || runtime.pending.value
      ? '上次请求结果尚未确认，请打开「构建与进程」确认。'
      : '')
  const canRun = computed(
    () =>
      !!target() &&
      !attempt.value &&
      builds.loaded.value &&
      runtime.loaded.value &&
      !builds.busy.value &&
      !runtime.busy.value &&
      !builds.loading.value &&
      !runtime.loading.value &&
      !problem(),
  )
  const canCancel = computed(() => attempt.value?.phase === 'building')
  function finish(text: string, show = false) {
    attempt.value = null
    message.value = text
    if (show) preview()
  }
  function cancel() {
    if (canCancel.value)
      finish('已取消后续自动启动；已提交的构建继续执行，可在「构建与进程」取消构建。')
  }
  async function launch(own: Attempt, buildId: string) {
    own.phase = 'launching'
    message.value = '正在确认运行状态并启动应用…'
    await runtime.refresh()
    if (!current(own)) return
    if (problem() || !runtime.canStart.value) {
      finish(problem() || '已有活动进程或状态尚未确认，请打开「构建与进程」检查。')
      return
    }
    await runtime.start(buildId)
    if (!current(own)) return
    if (problem()) return finish(problem())
    own.phase = 'starting'
    message.value = '正在等待本机进程就绪…'
    advance()
  }
  function advance() {
    const own = attempt.value
    if (!own || !current(own) || ['checking', 'launching'].includes(own.phase)) return
    if (builds.busy.value || runtime.busy.value || builds.loading.value || runtime.loading.value)
      return
    if (problem()) return finish(problem())
    if (own.phase === 'building') {
      const build = builds.build.value
      if (!build || build.id !== own.buildId)
        return finish('构建记录已变化，未启动应用；请重新确认。')
      if (build.status === 'SUCCEEDED') void launch(own, build.id)
      else if (!buildActive(build) || build.status === 'CANCELLING')
        finish('构建未成功或已取消，未启动应用。请打开「构建与进程」检查。')
    } else {
      const instance = runtime.instance.value
      if (
        !instance ||
        instance.buildId !== own.buildId ||
        instance.versionId !== target()?.versionId
      )
        return finish('运行记录已变化，请刷新并确认当前进程。')
      if (instance.status === 'RUNNING') finish('应用进程运行中，已切换至本机预览。', true)
      else if (!['QUEUED', 'STARTING'].includes(instance.status))
        finish('进程未就绪或已停止，请打开「构建与进程」检查。')
    }
  }
  async function run() {
    if (!canRun.value) return
    attempt.value = { key: key(), buildId: null, phase: 'checking' }
    const own = attempt.value
    message.value = '正在检查构建和运行状态…'
    await Promise.all([builds.refresh(), runtime.refresh()])
    if (!current(own)) return
    if (problem()) return finish(problem())
    const instance = runtime.instance.value
    if (instance && instanceActive(instance)) {
      if (instance.versionId !== target()?.versionId || instance.status === 'STOPPING')
        return finish('请先停止旧进程并等待退出，再运行当前版本。')
      own.buildId = instance.buildId
      own.phase = 'starting'
      message.value = '正在等待已有本机进程就绪…'
      advance()
      return
    }
    if (!buildActive(builds.build.value) && builds.build.value?.status !== 'SUCCEEDED') {
      await builds.submit()
      if (!current(own)) return
      if (problem()) return finish(problem())
    }
    own.buildId = builds.build.value?.id ?? null
    own.phase = 'building'
    message.value = '正在构建工程，成功后将自动启动应用。离开页面会取消后续自动启动。'
    advance()
  }
  watch(
    () => [
      builds.build.value,
      builds.busy.value,
      builds.loading.value,
      builds.error.value,
      builds.pending.value,
      runtime.instance.value,
      runtime.busy.value,
      runtime.loading.value,
      runtime.error.value,
      runtime.pending.value,
    ],
    advance,
  )
  watch(
    key,
    () => {
      attempt.value = null
      message.value = '点击后将构建工程、启动本机进程并打开预览；不会调用模型或自动发布。'
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    attempt.value = null
  })
  return { active: computed(() => !!attempt.value), canRun, canCancel, message, run, cancel }
}
