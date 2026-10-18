import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ToolDisclosure from './ToolDisclosure.vue'

it('closes on Escape and outside pointer, preserving content and restoring keyboard focus', async () => {
  const wrapper = mount(ToolDisclosure, {
    props: { label: '工具' },
    slots: { default: '<input aria-label="草稿" value="保留输入" />' },
    attachTo: document.body,
  })
  try {
    const details = wrapper.get('details').element
    const input = wrapper.get('input').element
    details.open = true
    input.focus()
    await wrapper.get('input').trigger('keydown', { key: 'Escape' })
    expect(details.open).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('summary').element)
    details.open = true
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(details.open).toBe(false)
    expect(wrapper.get('input').element).toBe(input)
    expect(input.value).toBe('保留输入')
  } finally {
    wrapper.unmount()
  }
})
