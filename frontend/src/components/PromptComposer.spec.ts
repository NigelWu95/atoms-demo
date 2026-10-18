import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PromptComposer from './PromptComposer.vue'

describe('PromptComposer', () => {
  it('keeps the full hint available in a closed disclosure only in compact workbench mode', async () => {
    const wrapper = mount(PromptComposer, {
      props: { modelValue: '继续修改', hint: '完整的数据与隐私说明', compact: true },
    })
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.get('#prompt-hint').text()).toBe('完整的数据与隐私说明')
    expect(wrapper.get('textarea').attributes('aria-describedby')).toBe('prompt-hint')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toEqual([['继续修改']])
    await wrapper.setProps({ compact: false })
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.get('#prompt-hint').text()).toBe('完整的数据与隐私说明')
    wrapper.unmount()
  })
  it('does not submit an empty or whitespace-only prompt', async () => {
    const wrapper = mount(PromptComposer, { props: { modelValue: '   ' } })
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('submits a trimmed prompt on Enter', async () => {
    const wrapper = mount(PromptComposer, { props: { modelValue: '  做一个待办清单  ' } })
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('submit')).toEqual([['做一个待办清单']])
  })

  it('preserves Shift+Enter and IME composition instead of submitting', async () => {
    const wrapper = mount(PromptComposer, { props: { modelValue: '中文输入' } })
    const input = wrapper.get('textarea')
    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    await input.trigger('compositionstart')
    await input.trigger('keydown', { key: 'Enter' })
    await input.trigger('compositionend')
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    await input.trigger('keydown', { key: 'Enter', keyCode: 229 })
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('rejects overlong input and disabled submission', async () => {
    const wrapper = mount(PromptComposer, { props: { modelValue: 'a'.repeat(8001) } })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
    await wrapper.setProps({ modelValue: '有效需求', disabled: true })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
