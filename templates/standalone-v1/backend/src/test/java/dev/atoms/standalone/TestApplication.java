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
                <p>SDK 存储计数（需要数据服务）</p><button id=count>0</button>
                <p id=storage-status role=status></p></main>
                """));
        files.put("application/styles.css", bytes("""
                body { color: #172017; background: #fff; font-family: sans-serif; margin: 0; padding: 20px; }
                h1 { font-size: 24px; } p { line-height: 1.7; }
                button { min-height: 44px; padding: 10px 16px; font: inherit; }
                button:focus-visible { outline: 3px solid #286a16; outline-offset: 3px; }
                """));
        files.put("application/main.js", bytes("""
                Atoms.ready(async sdk => {
                    const local = document.querySelector('#local-count');
                    let count = 0;
                    local.onclick = () => { local.textContent = '本页计数：' + ++count; };
                    const button = document.querySelector('#count');
                    const status = document.querySelector('#storage-status');
                    let state;
                    try { state = await sdk.storage.get(); }
                    catch {
                        button.disabled = true;
                        status.textContent = '数据服务不可用，存储计数器已停用。';
                        return;
                    }
                    button.textContent = state.value.count;
                    button.onclick = async () => {
                        try {
                            state = await sdk.storage.save({count: state.value.count + 1}, state.revision);
                            button.textContent = state.value.count;
                        } catch { status.textContent = '保存失败，当前数据未更新。'; }
                    };
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
        resources.put("runtime/content.sha256", bytes(RuntimeResources.contentId(resources) + "\n"));
        return resources;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
