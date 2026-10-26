<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useTodos, validTitle, type Todo } from './useTodos'

const withPriority = false
const { items, nextAfter, ready, busy, needsReload, error, notice, load, create, update } = useTodos(withPriority)
const title = ref(''), priority = ref(0), filter = ref<'all' | 'open' | 'done'>('all')
const editing = ref<Todo | null>(null), editTitle = ref(''), editPriority = ref(0)
const newInput = ref<HTMLInputElement>(), list = ref<HTMLElement>()
const labels = ['普通', '重要', '紧急']
const locked = computed(() => busy.value || needsReload.value || !ready.value)
const completed = computed(() => items.value.filter(item => item.completed).length)
const visible = computed(() => items.value.filter(item => filter.value === 'all' || item.completed === (filter.value === 'done')))
async function add() {
  if (editing.value) return
  if (await create(title.value, priority.value)) { title.value = ''; priority.value = 0; filter.value = 'all'; await nextTick(); newInput.value?.focus() }
}
async function edit(item: Todo) {
  editing.value = { ...item }; editTitle.value = item.title; editPriority.value = item.priority ?? 0
  await nextTick(); list.value?.querySelector<HTMLInputElement>('#edit-title')?.focus()
}
async function finishEdit() {
  const id = editing.value?.id; editing.value = null; await nextTick()
  list.value?.querySelector<HTMLButtonElement>(`[data-todo-id="${id}"] [data-test="edit"]`)?.focus()
}
async function save() {
  if (editing.value && await update(editing.value, editTitle.value, editing.value.completed, editPriority.value)) await finishEdit()
}
async function refresh() { if (await load()) editing.value = null }
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <main id="main" tabindex="-1" :data-sample-version="withPriority ? '2' : '1'">
    <header class="heading">
      <div><p class="eyebrow">DAILY NOTES · 日常待办</p><h1>今天，先做这几件。</h1><p class="intro">把想做的事写下来，一件一件完成。</p></div>
      <span class="brand-mark" aria-hidden="true">✓</span>
    </header>
    <form class="composer" data-test="create-form" @submit.prevent="add">
      <label for="new-title">添加待办</label>
      <div class="compose-fields">
        <input id="new-title" ref="newInput" v-model="title" :disabled="busy || !!editing" placeholder="例如：整理周五的演示材料" autocomplete="off" aria-describedby="title-hint" />
        <label v-if="withPriority" class="priority-select">优先级<select id="new-priority" v-model.number="priority" :disabled="busy || !!editing"><option v-for="(label, value) in labels" :key="value" :value="value">{{ label }}</option></select></label>
        <button data-test="add" :disabled="locked || !!editing || !validTitle(title)">添加待办 <span aria-hidden="true">＋</span></button>
      </div>
      <p id="title-hint" class="note">{{ [...title.trim()].length }} / 160 字</p>
    </form>
    <div class="feedback"><p role="status" aria-live="polite">{{ notice }}</p><p v-if="error" class="error" role="alert">{{ error }}</p></div>
    <section class="tasks" aria-labelledby="tasks-title" :aria-busy="busy">
      <div class="list-heading"><div><h2 id="tasks-title">我的待办</h2><p class="note">已加载 {{ items.length }} 项 · {{ completed }} 项已完成</p></div>
        <button class="secondary" type="button" data-test="refresh" :disabled="busy" @click="refresh">{{ editing ? '放弃编辑并刷新' : error ? '重新加载' : '刷新' }}</button>
      </div>
      <div class="filters" role="group" aria-label="筛选已加载的待办">
        <button v-for="choice in (['all', 'open', 'done'] as const)" :key="choice" type="button" :data-test="`filter-${choice}`" :aria-pressed="filter === choice" :disabled="!!editing" @click="filter = choice">{{ choice === 'all' ? '全部' : choice === 'open' ? '待完成' : '已完成' }}</button>
      </div>
      <div v-if="!ready && busy" class="empty">正在加载你的待办…</div>
      <div v-else-if="ready && !visible.length" class="empty"><span aria-hidden="true">✓</span><h3>{{ items.length ? '当前筛选下没有待办' : '还没有待办，从一件小事开始。' }}</h3><p class="note">{{ items.length ? '切换筛选，查看其他待办。' : '在上方写下今天想完成的第一件事。' }}</p></div>
      <ul ref="list" class="todo-list">
        <li v-for="item in visible" :key="item.id" data-test="todo" :data-todo-id="item.id" :class="{ complete: item.completed }">
          <form v-if="editing?.id === item.id" class="edit-form" data-test="edit-form" @submit.prevent="save">
            <label for="edit-title">修改待办</label><input id="edit-title" v-model="editTitle" :disabled="busy" />
            <div class="edit-actions"><label v-if="withPriority" class="priority-select">优先级<select id="edit-priority" v-model.number="editPriority" :disabled="busy"><option v-for="(label, value) in labels" :key="value" :value="value">{{ label }}</option></select></label>
              <button data-test="save" :disabled="locked || !validTitle(editTitle)">保存修改</button><button type="button" class="secondary" :disabled="busy" @click="finishEdit">取消</button>
            </div>
          </form>
          <template v-else>
            <button type="button" class="check" data-test="toggle" role="checkbox" :aria-checked="item.completed" :aria-label="`${item.completed ? '标记未完成' : '标记完成'}：${item.title}`" :disabled="locked || !!editing" @click="update(item, item.title, !item.completed)"><span aria-hidden="true">{{ item.completed ? '✓' : '' }}</span></button>
            <div class="todo-copy"><p class="todo-title">{{ item.title }}</p><span v-if="withPriority" class="priority-badge" :class="`priority-${item.priority}`">{{ labels[item.priority ?? 0] }}</span></div>
            <button type="button" class="secondary edit-button" data-test="edit" :disabled="locked || !!editing" :aria-label="`编辑：${item.title}`" @click="edit(item)">编辑</button>
          </template>
        </li>
      </ul>
      <button v-if="nextAfter !== null" class="secondary more" type="button" data-test="more" :disabled="busy || needsReload || !!editing" @click="load(true)">加载更多待办</button>
    </section>
    <footer class="note">不必一次做完。每完成一件，就向前一步。</footer>
  </main>
</template>

<style src="./app.css"></style>
<style scoped>
.heading { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 2rem; }
.brand-mark { display: grid; place-items: center; flex: none; width: 3rem; height: 3rem; border-radius: 1rem; background: var(--accent); color: var(--on-accent); font-size: 1.5rem; }
.composer, .tasks { border: 1px solid var(--border); border-radius: .875rem; background: var(--surface); }
.composer { padding: 1.25rem; }
label { display: block; font-size: .875rem; font-weight: 600; }
.compose-fields { display: flex; align-items: end; gap: .75rem; margin: .5rem 0; }
input, select { min-width: 0; width: 100%; min-height: 46px; font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--border); border-radius: .5rem; padding: .625rem .75rem; }
input::placeholder { color: var(--muted); }
input:focus-visible, select:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
input:disabled, select:disabled { opacity: .7; }
.priority-select { flex: 0 0 6rem; }
.feedback { padding: .75rem .25rem; font-size: .875rem; min-height: 3rem; color: var(--muted); }
.list-heading { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1.25rem; }
h2 { margin: 0; }
.secondary { background: transparent; color: var(--text); border-color: var(--border); }
.filters { display: flex; gap: .25rem; padding: 0 1.25rem .75rem; border-bottom: 1px solid var(--border); }
.filters button { background: transparent; color: var(--muted); border-color: transparent; padding: .5rem .75rem; }
.filters button[aria-pressed="true"] { color: var(--accent); background: var(--bg); border-color: var(--border); }
.todo-list { margin: 0; padding: 0; list-style: none; }
.todo-list li { display: flex; align-items: center; gap: .75rem; padding: 1rem 1.25rem; margin: 0; border-bottom: 1px solid var(--border); }
.todo-list li:last-child { border-bottom: 0; }
.todo-copy { flex: 1; min-width: 0; }
.todo-title { overflow-wrap: anywhere; }
.complete .todo-title { color: var(--muted); text-decoration: line-through; }
.check { width: 44px; min-width: 44px; padding: .5rem; background: transparent; border: 0; }
.check span { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid var(--muted); border-radius: .4rem; }
.check[aria-checked="true"] span { background: var(--accent); color: var(--on-accent); border-color: var(--accent); }
.priority-badge { display: inline-block; margin-top: .25rem; font-size: .75rem; color: var(--muted); }
.priority-1 { color: var(--accent); }.priority-2 { color: #ffc4aa; }
.edit-button { font-size: .875rem; padding: .5rem .75rem; }
.edit-form { width: 100%; }.edit-form input { margin: .5rem 0 .75rem; }
.edit-actions { display: flex; align-items: end; flex-wrap: wrap; gap: .5rem; }
.edit-actions .priority-select { margin-right: auto; }
.empty { padding: 2.5rem 1rem; text-align: center; }
.empty > span { display: inline-block; color: var(--accent); margin-bottom: .75rem; font-size: 1.5rem; }
.empty h3 { margin: 0 0 .5rem; font-size: 1rem; }
.more { display: block; margin: 1rem auto; }
footer { text-align: center; margin: 1.5rem 0 0; }
@media (max-width: 36rem) {
  .heading { align-items: start; }.brand-mark { display: none; }
  .compose-fields { flex-wrap: wrap; }.compose-fields > input { flex-basis: 100%; }
  .compose-fields > button { flex: 1; }.compose-fields .priority-select { flex: 1; }
  .list-heading { padding: 1rem; flex-wrap: wrap; }.filters { padding-inline: .75rem; }
  .todo-list li { padding: .875rem .75rem; gap: .25rem; }.composer { padding: 1rem; }
}
</style>
