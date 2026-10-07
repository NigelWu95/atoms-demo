package dev.atoms.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

final class TodoFixture {
    static JsonNode source(ObjectMapper json) {
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", "手工测试待办").put("summary", "固定测试产物 · 非 AI 生成；通过 SDK 保存到独立测试数据库。")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").putArray("todos");
        source.putObject("files").put("view.html", resource("todo.html"))
                .put("styles.css", resource("todo.css")).put("main.js", resource("todo.js"));
        return source;
    }
    private static String resource(String name) {
        try { return new ClassPathResource("fixtures/" + name).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Fixed test resource unavailable"); }
    }
}
