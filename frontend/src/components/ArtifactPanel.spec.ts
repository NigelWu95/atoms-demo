import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import ArtifactPanel from './ArtifactPanel.vue'
import { getVersion, listVersions, restoreVersion, type Version } from '../api/artifacts'
import { getProject, type Project } from '../api/projects'
import { ApiFailure } from '../api/client'
import { useSessionStore } from '../stores/session'

vi.mock('../api/artifacts', () => ({
  getVersion: vi.fn(),
  listVersions: vi.fn(),
  restoreVersion: vi.fn(),
}))
vi.mock('../api/projects', () => ({ getProject: vi.fn() }))
const project: Project = {
  id: 'p',
  name: '恢复项目',
  ownerId: 'owner',
  projectRevision: 2,
  currentVersionId: 'v2',
  createdAt: '',
  updatedAt: '',
}
function version(id: string, versionNo: number): Version {
  return {
    id,
    projectId: 'p',
    versionNo,
    createdAt: '',
    artifact: {
      formatVersion: 1,
      manifest: {
        title: '测试应用',
        summary: '',
        runtime: 'web-static-v1',
        stateSchemaVersion: 1,
        initialState: {},
      },
      files: { 'view.html': '<main>测试</main>', 'styles.css': '', 'main.js': '' },
    },
  }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(listVersions).mockResolvedValue({
    items: [version('v2', 2), version('v1', 1)],
    nextCursor: null,
  })
  vi.mocked(getVersion).mockImplementation(async (_, id) => version(id, id === 'v1' ? 1 : 2))
  vi.mocked(restoreVersion).mockResolvedValue(version('v3', 3))
  vi.mocked(getProject).mockResolvedValue({
    ...project,
    currentVersionId: 'v3',
    projectRevision: 3,
  })
})
async function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore().state = 'ready'
  const wrapper = mount(ArtifactPanel, {
    props: { project },
    global: { plugins: [pinia], stubs: { PreviewFrame: true, RouterLink: true } },
  })
  await flushPromises()
  await wrapper.get('select').setValue('v1')
  await flushPromises()
  return wrapper
}
it('requires confirmation, restores the selected version and refreshes the project pointer', async () => {
  const wrapper = await open()
  try {
    await wrapper.get('button[data-action="restore"]').trigger('click')
    expect(wrapper.text()).toContain('不回滚业务数据')
    expect(wrapper.text()).toContain('不自动发布')
    const submit = wrapper.get('button[data-action="confirm-restore"]')
    expect(submit.attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await submit.trigger('click')
    await flushPromises()
    expect(restoreVersion).toHaveBeenCalledWith(
      'p',
      { sourceVersionId: 'v1', expectedProjectRevision: 2 },
      expect.any(String),
    )
    expect(wrapper.emitted('changed')?.[0]?.[0]).toMatchObject({
      currentVersionId: 'v3',
      projectRevision: 3,
    })
    expect(wrapper.text()).toContain('已恢复为 V3')
  } finally {
    wrapper.unmount()
  }
})
it('keeps the original restore intent when the response is lost', async () => {
  vi.mocked(restoreVersion).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '结果尚不确定'))
  const wrapper = await open()
  try {
    await wrapper.get('button[data-action="restore"]').trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('button[data-action="confirm-restore"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('select').attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('changed')).toBeUndefined()
    await wrapper.get('button[data-action="retry-restore"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(restoreVersion).mock.calls[0]).toEqual(vi.mocked(restoreVersion).mock.calls[1])
    expect(wrapper.emitted('changed')).toHaveLength(1)
  } finally {
    wrapper.unmount()
  }
})

it('keeps the current preview on a version conflict and offers an explicit refresh', async () => {
  vi.mocked(restoreVersion).mockRejectedValueOnce(
    new ApiFailure('BASE_VERSION_CONFLICT', '当前代码版本已经变化', 409),
  )
  const wrapper = await open()
  try {
    await wrapper.get('button[data-action="restore"]').trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('button[data-action="confirm-restore"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('当前代码版本已经变化')
    expect(wrapper.emitted('changed')).toBeUndefined()
    expect(wrapper.find('button[data-action="retry-restore"]').exists()).toBe(false)
    expect(wrapper.get('select').element.value).toBe('v1')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新项目状态')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.emitted('changed')?.[0]?.[0]).toMatchObject({ currentVersionId: 'v3' })
  } finally {
    wrapper.unmount()
  }
})

it('replays the confirmed intent if fetching the updated project fails', async () => {
  vi.mocked(getProject).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR', '连接中断'))
  const wrapper = await open()
  try {
    await wrapper.get('button[data-action="restore"]').trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('button[data-action="confirm-restore"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('changed')).toBeUndefined()
    await wrapper.get('button[data-action="retry-restore"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(restoreVersion).mock.calls[0]).toEqual(vi.mocked(restoreVersion).mock.calls[1])
    expect(wrapper.emitted('changed')).toHaveLength(1)
  } finally {
    wrapper.unmount()
  }
})
