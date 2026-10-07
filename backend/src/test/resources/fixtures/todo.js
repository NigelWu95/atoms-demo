Atoms.ready(async (sdk) => {
    const input = document.querySelector('#title');
    const add = document.querySelector('#add');
    const list = document.querySelector('#todos');
    const status = document.querySelector('#status');
    const retry = document.querySelector('#retry');
    const latest = document.querySelector('#latest');
    let state = await sdk.storage.get();
    let draft = null, busy = false;
    function render(value) {
        list.replaceChildren();
        document.querySelector('#empty').hidden = value.todos.length > 0;
        for (const todo of value.todos) {
            const item = document.createElement('li');
            const label = document.createElement('label');
            const check = document.createElement('input');
            check.type = 'checkbox'; check.checked = todo.done; check.disabled = busy || draft !== null;
            const title = document.createElement('span'); title.textContent = todo.title;
            check.addEventListener('change', () => {
                draft = { todos: state.value.todos.map((old) => old.id === todo.id ? { ...old, done: check.checked } : old) };
                void save();
            });
            label.append(check, title); item.append(label); list.append(item);
        }
        add.disabled = busy || draft !== null;
    }
    async function save() {
        if (busy || !draft) return;
        busy = true; retry.hidden = true; latest.hidden = true;
        status.textContent = '正在保存…'; render(draft);
        try {
            state = await sdk.storage.save(draft, state.revision);
            draft = null; input.value = ''; status.textContent = '已保存。刷新后数据仍在。';
        } catch (error) {
            status.textContent = error.message;
            retry.hidden = false;
            latest.hidden = !['STATE_CONFLICT', 'READ_ONLY_PREVIEW', 'STALE_APP_VERSION', 'INVALID_INPUT'].includes(error.code);
        } finally { busy = false; render(draft || state.value); }
    }
    add.addEventListener('click', () => {
        if (busy || draft || !input.value.trim()) return;
        draft = { todos: [...state.value.todos, { id: crypto.randomUUID(), title: input.value.trim(), done: false }] };
        void save();
    });
    retry.addEventListener('click', () => { void save(); });
    latest.addEventListener('click', async () => {
        latest.disabled = true;
        try {
            state = await sdk.storage.get(); draft = null;
            retry.hidden = true; latest.hidden = true; status.textContent = '已读取最新数据，输入框内容仍保留。'; render(state.value);
        } catch (error) { status.textContent = error.message; }
        finally { latest.disabled = false; }
    });
    render(state.value); status.textContent = '数据已读取。';
});
