import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import PreviewFrame from './PreviewFrame.vue'
import { createRuntimeBridge } from '../runtime/bridge'
import { getPreviewReport, savePreviewReport } from '../api/previewReports'
import type { Version } from '../api/artifacts'
import { ApiFailure } from '../api/client'

vi.mock('../runtime/bridge', () => ({ createRuntimeBridge: vi.fn() }))
vi.mock('../api/previewReports', () => ({ getPreviewReport: vi.fn(), savePreviewReport: vi.fn() }))
const version: Version = {
  id: 'version-1',
  projectId: 'project-1',
  versionNo: 1,
  createdAt: '2026-10-07T00:00:00Z',
  artifact: {
    formatVersion: 1,
    manifest: {
      title: '检查用例',
      summary: '测试',
      runtime: 'web-static-v1',
      stateSchemaVersion: 1,
      initialState: {},
    },
    files: {
      'view.html': '<main>应用</main>',
      'styles.css': '',
      'main.js': 'Atoms.ready(() => {});',
    },
  },
}
const wrappers: ReturnType<typeof mount>[] = []
function setup(source = version.artifact.files['main.js']) {
  const wrapper = mount(PreviewFrame, {
    props: {
      version: {
        ...version,
        artifact: { ...version.artifact, files: { ...version.artifact.files, 'main.js': source } },
      },
      mode: 'CURRENT',
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(createRuntimeBridge).mockReturnValue({ start: vi.fn(), dispose: vi.fn() })
  vi.mocked(getPreviewReport).mockResolvedValue({
    status: 'UNKNOWN',
    reportId: null,
    checks: [],
    errors: [],
    viewport: null,
    createdAt: null,
  })
  vi.mocked(savePreviewReport).mockResolvedValue({ reportId: 'report-1', status: 'PASSED' })
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
it('moves tools into the toolbar without restarting the frame or losing observations', async () => {
  const wrapper = setup()
  const target = document.createElement('div')
  document.body.append(target)
  try {
    await flushPromises()
    const frame = wrapper.get('iframe').element
    vi.mocked(createRuntimeBridge).mock.calls[0]![3]('ready', '应用已启动')
    await wrapper.setProps({ toolsTarget: target })
    expect(target.textContent).toContain('本次观察：检查通过')
    expect(wrapper.get('iframe').element).toBe(frame)
    expect(createRuntimeBridge).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ toolsTarget: null })
    expect(wrapper.text()).toContain('本次观察：检查通过')
    expect(wrapper.get('iframe').element).toBe(frame)
    expect(createRuntimeBridge).toHaveBeenCalledTimes(1)
  } finally {
    target.remove()
  }
})
it('keeps report failures visible when report controls are moved to a collapsed tool', async () => {
  vi.mocked(getPreviewReport).mockRejectedValue(new ApiFailure('NETWORK_ERROR', '读取报告失败'))
  const wrapper = setup()
  const target = document.createElement('div')
  document.body.append(target)
  try {
    await wrapper.setProps({ toolsTarget: target })
    await flushPromises()
    expect(wrapper.text()).toContain('读取报告失败')
    expect(target.textContent).toContain('读取报告失败')
  } finally {
    target.remove()
  }
})
it('keeps observed errors visible outside tools even after a later successful data save', async () => {
  const wrapper = setup()
  const target = document.createElement('div')
  document.body.append(target)
  try {
    await wrapper.setProps({ toolsTarget: target })
    await flushPromises()
    const report = vi.mocked(createRuntimeBridge).mock.calls[0]![3]
    report('error', '先前观察到的错误')
    report('saved', '数据已保存')
    await flushPromises()
    expect(wrapper.text()).toContain('先前观察到的错误')
  } finally {
    target.remove()
  }
})
it('does not mount or execute an invalid script and allows saving a failure report', async () => {
  const wrapper = setup('const broken = ;')
  await flushPromises()
  expect(wrapper.find('iframe').exists()).toBe(false)
  expect(createRuntimeBridge).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('语法检查未通过')
  vi.mocked(savePreviewReport).mockResolvedValue({ reportId: 'report-1', status: 'FAILED' })
  await wrapper.get('[data-test="save-preview-report"]').trigger('click')
  await flushPromises()
  expect(savePreviewReport).toHaveBeenCalledWith(
    'project-1',
    'version-1',
    expect.objectContaining({ status: 'FAILED', checks: [] }),
    expect.any(String),
  )
})
it('offers explicit repair preparation only for a permitted current failure, never raw errors', async () => {
  const wrapper = setup('const broken = ;')
  await flushPromises()
  expect(wrapper.find('[data-action="prepare-repair"]').exists()).toBe(false)
  await wrapper.setProps({ repairable: true })
  expect(wrapper.emitted('repair')).toBeUndefined()
  await wrapper.get('[data-action="prepare-repair"]').trigger('click')
  expect(wrapper.emitted('repair')).toEqual([['SYNTAX']])
  expect(savePreviewReport).not.toHaveBeenCalled()
  await wrapper.setProps({ mode: 'HISTORY' })
  expect(wrapper.find('[data-action="prepare-repair"]').exists()).toBe(false)
})
it('requires SDK startup, latches runtime failures, and keeps checks separate from data saves', async () => {
  const wrapper = setup()
  await flushPromises()
  await wrapper.get('iframe').trigger('load')
  expect(wrapper.get('[data-test="save-preview-report"]').attributes('disabled')).toBeDefined()
  const report = vi.mocked(createRuntimeBridge).mock.calls[0]![3]
  report('ready', '应用已启动')
  await flushPromises()
  expect(wrapper.text()).toContain('本次观察：检查通过')
  report('error', '应用运行错误：test error')
  report('saved', '数据保存成功')
  report('ready', '重复 ready')
  await flushPromises()
  expect(wrapper.text()).toContain('本次观察：检查失败')
  expect(wrapper.text()).toContain('test error')
})
it('retries an unknown save with its original body/key while newer errors remain unsaved', async () => {
  const wrapper = setup()
  await flushPromises()
  const report = vi.mocked(createRuntimeBridge).mock.calls[0]![3]
  report('ready', '应用已启动')
  await flushPromises()
  vi.mocked(savePreviewReport).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '响应丢失'))
  await wrapper.get('[data-test="save-preview-report"]').trigger('click')
  await flushPromises()
  report('error', '晚到错误')
  await flushPromises()
  await wrapper.get('[data-test="save-preview-report"]').trigger('click')
  await flushPromises()
  expect(vi.mocked(savePreviewReport).mock.calls[1]).toEqual(
    vi.mocked(savePreviewReport).mock.calls[0],
  )
  expect(wrapper.text()).toContain('当前结果尚未保存')
  vi.mocked(savePreviewReport).mockResolvedValue({ reportId: 'report-2', status: 'FAILED' })
  await wrapper.get('[data-test="save-preview-report"]').trigger('click')
  await flushPromises()
  expect(vi.mocked(savePreviewReport).mock.calls[2]![2].status).toBe('FAILED')
  expect(vi.mocked(savePreviewReport).mock.calls[2]![3]).not.toBe(
    vi.mocked(savePreviewReport).mock.calls[0]![3],
  )
})
it('ignores runtime messages from the frame replaced by a reload', async () => {
  const wrapper = setup()
  await flushPromises()
  const old = vi.mocked(createRuntimeBridge).mock.calls[0]![3]
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重新加载')!
    .trigger('click')
  vi.mocked(createRuntimeBridge).mock.calls[1]![3]('ready', '应用已启动')
  old('error', '过期错误')
  await flushPromises()
  expect(wrapper.text()).toContain('本次观察：检查通过')
  expect(wrapper.text()).not.toContain('过期错误')
})
it('does not let a slow initial report read replace the newly saved observation', async () => {
  let finish!: (value: Awaited<ReturnType<typeof getPreviewReport>>) => void
  vi.mocked(getPreviewReport).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const wrapper = setup()
  await flushPromises()
  vi.mocked(createRuntimeBridge).mock.calls[0]![3]('ready', '应用已启动')
  await flushPromises()
  await wrapper.get('[data-test="save-preview-report"]').trigger('click')
  await flushPromises()
  finish({
    status: 'UNKNOWN',
    reportId: null,
    checks: [],
    errors: [],
    viewport: null,
    createdAt: null,
  })
  await flushPromises()
  expect(wrapper.text()).toContain('已保存报告：检查通过')
})
