package dev.atoms.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.project.ProjectService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class FixtureController {
    private final ArtifactService artifacts;
    private final ProjectService projects;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    public FixtureController(ArtifactService artifacts, ProjectService projects, IdempotencyService idempotency, ObjectMapper json) {
        this.artifacts = artifacts; this.projects = projects; this.idempotency = idempotency; this.json = json;
    }
    @GetMapping("/api/test-fixtures")
    Map<String, Boolean> available() { return Map.of("testOnly", true); }

    @PostMapping("/api/test-fixtures")
    ResponseEntity<JsonNode> create(Principal principal, @RequestBody JsonNode input,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!input.isObject() || input.size() != 1 || !input.path("fixture").isTextual()
                || !Set.of("counter", "closing-tags", "security", "history", "todo", "todo-history", "todo-review").contains(input.path("fixture").asText())) {
            throw new ApiException(422, "INVALID_FIXTURE", "仅允许固定的测试产物，不能提交任意源码。");
        }
        String fixture = input.path("fixture").asText();
        UUID owner = UUID.fromString(principal.getName());
        var result = idempotency.execute(owner, "POST", "/api/test-fixtures", key, input, () -> {
            var project = projects.create(owner, "测试产物 · " + fixture);
            var source = source(fixture);
            var first = artifacts.save(owner, project.id().toString(), 0, null, source);
            var nextSource = source.deepCopy();
            if (fixture.equals("todo-review")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) nextSource.path("files")).put("styles.css",
                        nextSource.path("files").path("styles.css").asText() + "\n/* V2 review fixture */\nh1{letter-spacing:.01em}");
            }
            var current = fixture.endsWith("history") || fixture.equals("todo-review")
                    ? artifacts.save(owner, project.id().toString(), 1, first.id(), nextSource) : first;
            return new IdempotencyService.Result(201, json.valueToTree(Map.of("project", projects.get(owner, project.id().toString()),
                    "version", current, "firstVersionId", first.id(), "testOnly", true)));
        });
        return ResponseEntity.status(result.status()).body(result.body());
    }
    private JsonNode source(String fixture) {
        if (fixture.startsWith("todo")) return TodoFixture.source(json);
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", "测试计数器").put("summary", "手工安全测试产物，不是 AI 生成；刷新会重置计数")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        var files = source.putObject("files");
        files.put("view.html", "<main><p>WEB-STATIC-V1 / TEST FIXTURE</p><h1>测试计数器</h1><p>点击下方按钮。刷新会归零，尚未接入数据保存。</p><button id=\"count\" aria-label=\"增加计数\">0</button><output id=\"checks\" aria-live=\"polite\"></output></main>");
        files.put("styles.css", "body{margin:0;background:#faf9f5;color:#20291b;font-family:system-ui}main{padding:2rem;max-width:36rem;margin:auto}p{line-height:1.6}button{font:600 2rem system-ui;padding:1rem 2rem;border:1px solid #657354;background:#dcf5a6;color:#20291b;border-radius:.5rem;cursor:pointer}button:focus-visible{outline:3px solid #20291b;outline-offset:4px}output{display:block;margin-top:1rem}");
        String script = "const counter=document.querySelector('#count');counter.addEventListener('click',()=>{counter.textContent=String(Number(counter.textContent)+1)});";
        if ("closing-tags".equals(fixture)) {
            files.put("styles.css", files.path("styles.css").asText() + "/* </style><script>document.body.dataset.breakout='css'</script> */");
            script += "const closing='</script><script>document.body.dataset.breakout=\"js\"</script>';document.querySelector('#checks').textContent=closing;";
        }
        if ("security".equals(fixture)) {
            script += "try{void parent.document.body;document.body.dataset.parentAccess='allowed'}catch{document.body.dataset.parentAccess='blocked'};"
                    + "fetch('/api/health').then(()=>{document.body.dataset.network='allowed'}).catch(()=>{document.body.dataset.network='blocked'});"
                    + "const remote=document.createElement('script');remote.src='/api/test-fixtures/external.js';remote.nonce='copied-nonce';"
                    + "remote.onerror=()=>{document.body.dataset.externalScript='blocked'};document.body.append(remote);";
        }
        files.put("main.js", script);
        return source;
    }

    @GetMapping("/test-lab")
    ResponseEntity<String> page(HttpServletResponse response) throws IOException { return resource("test-lab.html", "text/html;charset=UTF-8", response); }
    @GetMapping("/test-lab.js")
    ResponseEntity<String> script(HttpServletResponse response) throws IOException { return resource("test-lab.js", "application/javascript;charset=UTF-8", response); }
    private ResponseEntity<String> resource(String file, String type, HttpServletResponse response) throws IOException {
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'unsafe-inline'; img-src data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        return ResponseEntity.ok().header("Content-Type", type)
                .body(new ClassPathResource("fixtures/" + file).getContentAsString(StandardCharsets.UTF_8));
    }
}
