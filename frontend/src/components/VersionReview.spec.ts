import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import VersionReview from './VersionReview.vue'
import { getVersionReview } from '../api/versionReview'
vi.mock('../api/versionReview', () => ({ getVersionReview: vi.fn() }))
it('loads on demand and renders actual before/after code as escaped text', async () => {
  vi.mocked(getVersionReview).mockResolvedValue({
    versionId: 'v2',
    baseVersionId: 'v1',
    baseVersionNo: 1,
    restoredFromId: null,
    runId: 'r',
    request: '换颜色',
    changes: [
      { file: 'view.html', before: '<main>旧</main>', after: '<script>neverExecute()</script>' },
    ],
  })
  const wrapper = mount(VersionReview, { props: { projectId: 'p', versionId: 'v2' } })
  expect(getVersionReview).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('换颜色')
  expect(wrapper.text()).toContain('V1')
  expect(wrapper.text()).toContain('<script>neverExecute()</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  await wrapper.setProps({ versionId: 'v3' })
  expect(wrapper.text()).not.toContain('换颜色')
  wrapper.unmount()
})
it('discards stale responses after version changes', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof getVersionReview>>) => void
  vi.mocked(getVersionReview).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const wrapper = mount(VersionReview, { props: { projectId: 'p', versionId: 'v2' } })
  await wrapper.get('button').trigger('click')
  await wrapper.setProps({ versionId: 'v3' })
  resolve({
    versionId: 'v2',
    baseVersionId: null,
    baseVersionNo: null,
    restoredFromId: null,
    runId: null,
    request: 'stale',
    changes: [],
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('stale')
  wrapper.unmount()
})
