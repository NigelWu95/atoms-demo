import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import ProjectList from './ProjectList.vue'
import { listProjects, archiveProject, type Project } from '../api/projects'
import { useSessionStore } from '../stores/session'
vi.mock('../api/projects', () => ({ listProjects: vi.fn(), archiveProject: vi.fn() }))
const item: Project = {
  id: 'p',
  ownerId: 'owner',
  name: '2048',
  projectRevision: 1,
  currentVersionId: 'v',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(listProjects).mockResolvedValue({ items: [item], nextCursor: null })
})
async function setup() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.state = 'ready'
  session.identity = { userId: 'owner', isGuest: false, displayName: '测试用户' }
  const wrapper = mount(ProjectList, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  await flushPromises()
  return wrapper
}
it('searches with a fresh cursor and explicitly requests recent-edit ordering', async () => {
  const wrapper = await setup()
  expect(listProjects).toHaveBeenLastCalledWith(20, null, {
    q: '',
    sort: 'updated',
    archived: false,
  })
  await wrapper.get('input[type="search"]').setValue('2048')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(listProjects).toHaveBeenLastCalledWith(20, null, {
    q: '2048',
    sort: 'updated',
    archived: false,
  })
  expect(wrapper.text()).toContain('已生成')
  wrapper.unmount()
})
it('archives only the selected project and switches to the recoverable list', async () => {
  const wrapper = await setup()
  vi.mocked(archiveProject).mockResolvedValue({ ...item, archived: true })
  await wrapper.get('button[aria-label="归档 2048"]').trigger('click')
  await flushPromises()
  expect(archiveProject).toHaveBeenCalledWith('p', true)
  await wrapper.get('select[aria-label="项目范围"]').setValue('archived')
  await flushPromises()
  expect(listProjects).toHaveBeenLastCalledWith(20, null, {
    q: '',
    sort: 'updated',
    archived: true,
  })
  expect(wrapper.text()).toContain('恢复')
  wrapper.unmount()
})
it('uses account-aware empty copy', async () => {
  vi.mocked(listProjects).mockResolvedValue({ items: [], nextCursor: null })
  const wrapper = await setup()
  expect(wrapper.text()).toContain('当前账号')
  expect(wrapper.text()).not.toContain('访客空间')
  wrapper.unmount()
})
