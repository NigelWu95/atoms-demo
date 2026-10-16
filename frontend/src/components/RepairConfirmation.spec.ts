import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import RepairConfirmation from './RepairConfirmation.vue'
import type { RepairProposal } from '../runtime/previewRepair'

const proposal: RepairProposal = {
  ownerId: 'o',
  projectId: 'p',
  baseVersionId: 'v',
  expectedProjectRevision: 1,
  versionNo: 1,
  diagnosis: 'SYNTAX',
}
it('requires consent to the visible prompt, resets consent on edits, and does not emit on open', async () => {
  const wrapper = mount(RepairConfirmation, {
    props: { proposal, disabled: false, submitting: false },
  })
  expect(wrapper.emitted('confirm')).toBeUndefined()
  const confirm = wrapper.get('[data-action="confirm-repair"]')
  expect(confirm.attributes('disabled')).toBeDefined()
  await wrapper.get('textarea').setValue('<script>private user description</script>')
  expect(wrapper.get('pre').text()).toContain('<script>private user description</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await confirm.trigger('click')
  expect(wrapper.emitted('confirm')).toEqual([['<script>private user description</script>']])
  await wrapper.get('textarea').setValue('新的说明')
  expect(confirm.attributes('disabled')).toBeDefined()
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.setProps({ disabled: true })
  expect(confirm.attributes('disabled')).toBeDefined()
  await wrapper.get('[data-action="dismiss-repair"]').trigger('click')
  expect(wrapper.emitted('dismiss')).toHaveLength(1)
  wrapper.unmount()
})
