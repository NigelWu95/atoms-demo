import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import PublicationPanel from './PublicationPanel.vue'
import { useSessionStore } from '../stores/session'
import { getProject, type Project } from '../api/projects'
import { listVersions } from '../api/artifacts'
import { getPreviewReport } from '../api/previewReports'
import { getPublication, publish } from '../api/publication'

vi.mock('../api/projects', () => ({ getProject: vi.fn() }))
vi.mock('../api/artifacts', () => ({ listVersions: vi.fn() }))
vi.mock('../api/previewReports', () => ({ getPreviewReport: vi.fn() }))
vi.mock('../api/publication', () => ({
  getPublication: vi.fn(),
  publish: vi.fn(),
  unpublish: vi.fn(),
}))
const project: Project = {
  id: 'p',
  ownerId: 'o',
  name: '应用',
  currentVersionId: 'v2',
  projectRevision: 2,
  createdAt: '',
  updatedAt: '',
}
const published = {
  slug: 'a'.repeat(32),
  versionId: 'v1',
  versionNo: 1,
  status: 'PUBLISHED' as const,
  revision: 1,
  publicPath: '/a/' + 'a'.repeat(32),
  publicUrl: null,
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getProject).mockResolvedValue(project)
  vi.mocked(listVersions).mockResolvedValue({
    items: [{ id: 'v2', projectId: 'p', versionNo: 2, createdAt: '' }],
    nextCursor: null,
  })
  vi.mocked(getPublication).mockResolvedValue(published)
  vi.mocked(getPreviewReport).mockResolvedValue({
    status: 'PASSED',
    reportId: 'r',
    checks: ['SCRIPT_PARSE', 'SDK_STARTUP', 'RUNTIME_ERRORS'],
    errors: [],
    viewport: { width: 800, height: 600 },
    createdAt: '',
  })
})
afterEach(() => vi.unstubAllGlobals())
async function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore().state = 'ready'
  const wrapper = mount(PublicationPanel, { props: { project }, global: { plugins: [pinia] } })
  await wrapper.get('button').trigger('click')
  await flushPromises()
  return wrapper
}
it('shows current V2 versus published V1 and binds explicit update to the selected revisions', async () => {
  const wrapper = await open()
  try {
    expect(wrapper.text()).toContain('当前版本 V2 · 上次发布 V1')
    const update = wrapper.findAll('button').find((button) => button.text() === '更新已发布版本')!
    expect(update.attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    vi.mocked(publish).mockResolvedValue({
      ...published,
      versionId: 'v2',
      versionNo: 2,
      revision: 2,
    })
    await update.trigger('click')
    await flushPromises()
    expect(publish).toHaveBeenCalledWith(
      'p',
      { versionId: 'v2', expectedProjectRevision: 2, expectedPublicationRevision: 1 },
      expect.any(String),
    )
  } finally {
    wrapper.unmount()
  }
})
it('labels a local URL honestly and preserves a selectable link when clipboard permission is denied', async () => {
  const writeText = vi.fn().mockRejectedValue(new Error('denied'))
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const wrapper = await open()
  try {
    expect(wrapper.get('label[for="publication-url"]').text()).toBe('本地访问链接（尚未接入公网）')
    const input = wrapper.get<HTMLInputElement>('#publication-url')
    expect(input.attributes('readonly')).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '复制链接')!
      .trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(input.element.value)
    expect(wrapper.text()).toContain('请选中上方链接手动复制')
  } finally {
    wrapper.unmount()
  }
})
