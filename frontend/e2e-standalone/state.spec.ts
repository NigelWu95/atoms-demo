import { test, expect, ready, add, saved, state } from './fixtures'

test('assembled application saves and reloads without any platform origin', async ({
  page,
}, info) => {
  await ready(page)
  expect((await state(page)).revision).toBe(0)
  await add(page, '完全独立保存的待办')
  await saved(page)
  const first = await state(page)
  expect(first.revision).toBe(1)
  expect(first.value.todos).toHaveLength(1)
  await page.reload()
  const frame = page.frameLocator('iframe')
  await expect(frame.getByRole('listitem')).toHaveText('完全独立保存的待办')
  await frame.getByRole('checkbox', { name: '完全独立保存的待办' }).check()
  await saved(page)
  await page.reload()
  await expect(frame.getByRole('checkbox', { name: '完全独立保存的待办' })).toBeChecked()
  const latest = await state(page)
  expect(latest.revision).toBe(2)
  expect(latest.value.todos).toEqual([{ ...first.value.todos[0], done: true }])
  await page.screenshot({ path: info.outputPath('persisted-todo.png'), fullPage: true })
})

test('two tabs preserve the losing draft and do not overwrite the winning revision', async ({
  page,
  context,
}) => {
  await ready(page)
  const second = await context.newPage()
  await ready(second)
  await add(page, '先提交的待办')
  await saved(page)
  await add(second, '保留输入')
  await expect(second.getByRole('alert')).toContainText('数据已更新')
  const frame = second.frameLocator('iframe')
  await expect(frame.getByLabel('新待办', { exact: true })).toHaveValue('保留输入')
  expect((await state(page)).revision).toBe(1)
  expect((await state(page)).value.todos.map((todo: { title: string }) => todo.title)).toEqual([
    '先提交的待办',
  ])
  await frame.getByRole('button', { name: '读取最新数据', exact: true }).click()
  await expect(frame.getByRole('listitem')).toHaveText('先提交的待办')
  await expect(frame.getByLabel('新待办', { exact: true })).toHaveValue('保留输入')
  await frame.getByRole('button', { name: '添加待办', exact: true }).click()
  await saved(second)
  expect((await state(page)).revision).toBe(2)
  await page.reload()
  await expect(page.frameLocator('iframe').getByRole('listitem')).toHaveText([
    '先提交的待办',
    '保留输入',
  ])
})
