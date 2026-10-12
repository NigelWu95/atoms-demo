package dev.atoms.standalone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/** Explicit test-classpath entry point, never a production fallback or a HTTP fixture endpoint. */
public final class TestApplication {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Test launch does not accept command-line overrides.");
        var environment = TestDatabase.load();
        var application = application();
        application.setEnvironment(environment);
        application.run();
    }

    static SpringApplication application() { return application(resources()); }
    static SpringApplication application(RuntimeResources resources) {
        var application = new SpringApplication(StandaloneApplication.class);
        application.setAddCommandLineProperties(false);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("runtimeResources", resources));
        return application;
    }

    static Map<String, byte[]> files() {
        var files = new HashMap<String, byte[]>();
        files.put("application/manifest.json", bytes("""
                {"title":"计数器","summary":"独立测试应用","runtime":"web-static-v1",
                 "stateSchemaVersion":1,"initialState":{"count":0}}
                """));
        files.put("application/view.html", bytes("""
                <main><h1>计数交互</h1><p>本页计数只验证页面交互，刷新后归零，不保存数据。</p>
                <button id=local-count>本页计数：0</button>
                <p>SDK 存储计数</p><button id=count>0</button>
                <h2>持久化待办</h2><label for=todo-input>新待办</label>
                <input id=todo-input maxlength=200 disabled><button id=add-todo disabled>添加待办</button>
                <ul id=todos></ul><p id=empty-todos>暂无待办</p>
                <p id=storage-status role=status></p>
                <button id=retry-save disabled>重试原操作</button><button id=read-state disabled>读取最新数据</button></main>
                """));
        files.put("application/styles.css", bytes("""
                body { color: #172017; background: #fff; font-family: sans-serif; margin: 0; padding: 20px; }
                h1 { font-size: 24px; } p { line-height: 1.7; }
                button { min-height: 44px; padding: 10px 16px; font: inherit; }
                button:focus-visible, input:focus-visible { outline: 3px solid #286a16; outline-offset: 3px; }
                input { display: block; box-sizing: border-box; max-width: 100%; min-height: 44px; font: inherit; margin: 8px 0; }
                li { margin: 8px 0; overflow-wrap: anywhere; } li button { margin-left: 12px; }
                button:disabled { color: #586058; } #retry-save, #read-state { margin: 4px; }
                """));
        files.put("application/main.js", bytes("""
                Atoms.ready(async sdk => {
                    const local = document.querySelector('#local-count');
                    let count = 0;
                    local.onclick = () => { local.textContent = '本页计数：' + ++count; };
                    const button = document.querySelector('#count');
                    const status = document.querySelector('#storage-status');
                    const input = document.querySelector('#todo-input');
                    const add = document.querySelector('#add-todo');
                    const retry = document.querySelector('#retry-save');
                    const read = document.querySelector('#read-state');
                    const list = document.querySelector('#todos');
                    let state, intent = null, busy = false;
                    function controls() {
                        const blocked = busy || !!intent || !state;
                        button.disabled = input.disabled = add.disabled = blocked;
                        for (const action of list.querySelectorAll('button')) action.disabled = blocked;
                        retry.disabled = busy || !intent;
                        read.disabled = busy || !!intent;
                    }
                    function render() {
                        button.textContent = state.value.count;
                        list.replaceChildren();
                        const todos = state.value.todos || [];
                        document.querySelector('#empty-todos').hidden = todos.length > 0;
                        todos.forEach((todo, index) => {
                            const item = document.createElement('li');
                            const label = document.createElement(todo.done ? 's' : 'span');
                            label.textContent = todo.text;
                            const toggle = document.createElement('button');
                            toggle.textContent = todo.done ? '恢复' : '完成';
                            toggle.onclick = () => save({ ...state.value, todos: todos.map((entry, at) =>
                                at === index ? { ...entry, done: !entry.done } : entry) }, false);
                            item.append(label, toggle); list.append(item);
                        });
                        controls();
                    }
                    async function save(value, clearDraft) {
                        if (busy) return;
                        if (!intent) intent = { value: JSON.parse(JSON.stringify(value)), revision: state.revision, clearDraft };
                        busy = true; controls(); status.textContent = '正在保存…';
                        try {
                            state = await sdk.storage.save(intent.value, intent.revision);
                            if (intent.clearDraft) input.value = '';
                            intent = null; render(); status.textContent = '已保存';
                        } catch (error) {
                            if (['STATE_CONFLICT', 'STALE_APP_VERSION', 'INVALID_INPUT', 'IDEMPOTENCY_PAYLOAD_MISMATCH', 'READ_ONLY_PREVIEW'].includes(error.code)) {
                                intent = null; status.textContent = '保存被拒绝，输入已保留。请读取最新数据后再修改。';
                            } else status.textContent = '保存结果尚未确认，请点击“重试原操作”。';
                        } finally { busy = false; controls(); }
                    }
                    async function load() {
                        if (busy || intent) return;
                        busy = true; controls();
                        try { state = await sdk.storage.get(); render(); status.textContent = '已读取最新数据'; }
                        catch { status.textContent = '数据服务不可用，请手动重试读取。'; }
                        finally { busy = false; controls(); }
                    }
                    button.onclick = () => save({ ...state.value, count: state.value.count + 1 }, false);
                    add.onclick = () => {
                        if (input.value.trim()) save({ ...state.value, todos: [...(state.value.todos || []),
                            { text: input.value.trim(), done: false }] }, true);
                    };
                    retry.onclick = () => { if (intent) save(intent.value, intent.clearDraft); };
                    read.onclick = load;
                    controls(); await load();
                });
                """));
        return files;
    }

    static RuntimeResources resources() { return resources(files()); }
    static RuntimeResources resources(Map<String, byte[]> sources) {
        var files = resourceFiles(sources);
        return RuntimeResources.load(path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null);
    }
    static Map<String, byte[]> resourceFiles(Map<String, byte[]> sources) {
        var resources = new HashMap<>(sources);
        for (String path : new String[]{"runtime/bootstrap.js", "runtime/document.html"}) {
            try (var stream = TestApplication.class.getClassLoader().getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Test runtime resource is missing.");
                resources.put(path, stream.readAllBytes());
            } catch (IOException error) { throw new IllegalStateException("Test runtime resource could not be read."); }
        }
        // Minimal shell bytes for backend contract tests, not a production fallback or Vue build.
        resources.put("runtime/build.txt", bytes("0".repeat(64) + "\nstatic/assets/app.js\nstatic/index.html\n"));
        resources.put("static/assets/app.js", bytes("console.log('test shell');"));
        resources.put("static/index.html", bytes("<!doctype html><html><head><title>Test shell</title></head><body>Shell fixture</body></html>"));
        resources.put("runtime/content.sha256", bytes(RuntimeResources.contentId(resources) + "\n"));
        return resources;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
