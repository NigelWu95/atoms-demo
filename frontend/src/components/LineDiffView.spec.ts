import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import LineDiff from './LineDiff.vue'

it('renders safe, labelled additions and deletions and allows full source comparison', async () => {
  const wrapper = mount(LineDiff, {
    props: { file: 'view.html', before: '<main>old</main>', after: '<script>bad()</script>' },
  })
  expect(wrapper.get('table').text()).toContain('新增 1 行 · 删除 1 行')
  expect(wrapper.get('[data-kind="remove"]').text()).toContain('−')
  expect(wrapper.get('[data-kind="add"]').text()).toContain('+')
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.text()).toContain('<script>bad()</script>')
  await wrapper.get('button').trigger('click')
  expect(wrapper.find('table').exists()).toBe(false)
  expect(wrapper.get('pre[aria-label="view.html 修改前"]').text()).toBe('<main>old</main>')
  expect(wrapper.get('pre[aria-label="view.html 修改后"]').text()).toBe('<script>bad()</script>')
  await wrapper.setProps({ before: 'old', after: 'new' })
  expect(wrapper.find('table').exists()).toBe(true)
  wrapper.unmount()
})

it('can reveal unchanged context and falls back to full text for oversized diffs', async () => {
  const before = Array.from({ length: 20 }, (_, i) => String(i)).join('\n')
  const wrapper = mount(LineDiff, { props: { file: 'main.js', before, after: before + '\nnew' } })
  expect(wrapper.text()).toContain('省略 17 行未修改内容')
  await wrapper.get('input[type="checkbox"]').setValue(true)
  expect(wrapper.text()).not.toContain('省略')
  expect(wrapper.findAll('tbody tr')).toHaveLength(21)
  await wrapper.setProps({ before: 'a\n'.repeat(2000), after: '' })
  expect(wrapper.text()).toContain('差异较大，已切换为全文对比')
  expect(wrapper.find('table').exists()).toBe(false)
  expect(wrapper.findAll('pre')).toHaveLength(2)
  expect(wrapper.find('button').exists()).toBe(false)
  wrapper.unmount()
})
