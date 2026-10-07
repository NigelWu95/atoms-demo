import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PromptComposer from './PromptComposer.vue'

describe('PromptComposer', () => {
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
