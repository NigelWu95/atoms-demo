import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import GenerationRecovery from './GenerationRecovery.vue'

const pending = {
  key: 'original-key',
  preserveDraft: true,
  body: {
    prompt: '<img src=x onerror=alert(1)>',
    intent: 'MODIFY' as const,
    baseVersionId: 'original-version',
    expectedProjectRevision: 7,
  },
}
it('renders original request as text and exposes manual actions with accurate cost warnings', async () => {
  const wrapper = mount(GenerationRecovery, {
    props: {
      pending,
      savedAt: Date.UTC(2026, 9, 17),
      blocked: false,
      unavailable: false,
      disabled: false,
    },
  })
  expect(wrapper.text()).toContain(pending.body.prompt)
  expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.text()).toContain('original-version')
  expect(wrapper.text()).toContain('消耗模型额度')
  expect(wrapper.emitted()).toEqual({})
  await wrapper.get('[data-action="retry"]').trigger('click')
  expect(wrapper.emitted('retry')).toHaveLength(1)
  await wrapper.get('[data-action="discard"]').trigger('click')
  expect(wrapper.emitted('discard')).toHaveLength(1)
  await wrapper.setProps({ disabled: true })
  expect(wrapper.get('[data-action="retry"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-action="discard"]').attributes('disabled')).toBeDefined()
})
it('shows expiry/corruption and storage failure without inventing a retry', () => {
  const wrapper = mount(GenerationRecovery, {
    props: {
      blocked: true,
      unavailable: true,
      disabled: false,
    },
  })
  expect(wrapper.text()).toContain('过期、损坏或无法读取')
  expect(wrapper.text()).toContain('刷新后无法保证恢复')
  expect(wrapper.text()).not.toContain('仍可手动重试')
  expect(wrapper.find('[data-action="retry"]').exists()).toBe(false)
  expect(wrapper.find('[data-action="discard"]').exists()).toBe(true)
})
it('lets an accepted repair marker be cleared without retaining the original text', () => {
  const wrapper = mount(GenerationRecovery, {
    props: {
      savedAt: Date.now(),
      blocked: false,
      unavailable: false,
      disabled: false,
    },
  })
  expect(wrapper.text()).toContain('仅保留修复任务标记')
  expect(wrapper.find('[data-action="discard"]').exists()).toBe(true)
  expect(wrapper.find('[data-action="retry"]').exists()).toBe(false)
})
