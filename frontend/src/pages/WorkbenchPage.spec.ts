import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { h, ref } from 'vue'
import { expect, it, vi } from 'vitest'
import WorkbenchPage from './WorkbenchPage.vue'
import ArtifactPanel from '../components/ArtifactPanel.vue'
import ConversationPanel from '../components/ConversationPanel.vue'
import { useSessionStore } from '../stores/session'
import { getProject, type Project } from '../api/projects'

vi.mock('../api/projects', () => ({ getProject: vi.fn() }))
vi.mock('../composables/useProjectDraft', () => ({
  useProjectDraft: () => ({
    status: ref('saved'),
    error: ref(''),
    dirty: ref(false),
    retry: vi.fn(),
    reload: vi.fn(),
  }),
}))

it('retains repair confirmation on an unchanged project refresh but invalidates a new revision', async () => {
  const project: Project = {
    id: 'p',
    ownerId: 'owner',
    name: '测试',
    currentVersionId: 'v1',
    projectRevision: 1,
    createdAt: '',
    updatedAt: '',
  }
  vi.mocked(getProject).mockResolvedValue(project)
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  session.identity = { userId: 'owner', isGuest: true, displayName: '测试' }
  session.state = 'ready'
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/workbench/:id', component: WorkbenchPage }],
  })
  await router.push('/workbench/p')
  const wrapper = mount(
    { render: () => h(RouterView) },
    {
      global: {
        plugins: [pinia, router],
        stubs: {
          ArtifactPanel: true,
          ConversationPanel: true,
          ProjectHeading: true,
          PublicationPanel: true,
          RouterLink: true,
        },
      },
    },
  )
  try {
    await flushPromises()
    const proposal = {
      projectId: 'p',
      ownerId: 'owner',
      baseVersionId: 'v1',
      expectedProjectRevision: 1,
      versionNo: 1,
      diagnosis: 'SYNTAX',
    }
    wrapper.getComponent(ArtifactPanel).vm.$emit('repair', proposal)
    await flushPromises()
    expect(wrapper.getComponent(ConversationPanel).props('repair')).toEqual(proposal)
    wrapper.getComponent(ConversationPanel).vm.$emit('changed', { ...project })
    await flushPromises()
    expect(wrapper.getComponent(ConversationPanel).props('repair')).toEqual(proposal)
    wrapper.getComponent(ConversationPanel).vm.$emit('changed', { ...project, projectRevision: 2 })
    await flushPromises()
    expect(wrapper.getComponent(ConversationPanel).props('repair')).toBeNull()
  } finally {
    wrapper.unmount()
    disposePinia(pinia)
  }
})
