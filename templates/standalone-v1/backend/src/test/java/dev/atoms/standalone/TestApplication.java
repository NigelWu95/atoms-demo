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
        files.put("application/view.html", bytes("<main><button id=count>0</button></main>"));
        files.put("application/styles.css", bytes("body { color: black; }"));
        files.put("application/main.js", bytes("""
                Atoms.ready(async sdk => {
                    const button = document.querySelector('#count');
                    let state = await sdk.storage.get();
                    button.textContent = state.value.count;
                    button.onclick = async () => {
                        state = await sdk.storage.save({count: state.value.count + 1}, state.revision);
                        button.textContent = state.value.count;
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
