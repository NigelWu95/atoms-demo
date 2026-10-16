import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import GenerationProgress from './GenerationProgress.vue'
it('shows real build and repair stages without claiming browser validation', async () => {
  const wrapper = mount(GenerationProgress, { props: { stage: 'BUILDING', status: 'RUNNING' } })
  expect(wrapper.get('[aria-current="step"]').text()).toContain('生成代码')
  await wrapper.setProps({ stage: 'REPAIRING' })
  expect(wrapper.text()).toContain('修复')
  await wrapper.setProps({ stage: 'FAILED', status: 'FAILED' })
  expect(wrapper.find('[aria-current="step"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('未完成')
  await wrapper.setProps({ stage: 'SUCCEEDED', status: 'SUCCEEDED' })
  expect(wrapper.text()).toContain('需要实际操作预览')
})
