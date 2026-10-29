package dev.atoms.demo.run;

import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DEEPSEEK_API_KEY=test-only-key", "GENERATION_MAX_MODEL_CALLS=100000", "atoms.generation.worker-enabled=false",
                "atoms.project-workbench.default-mode=false"})
@ActiveProfiles("test")
class RunAdmissionIT {
    @LocalServerPort int port;
    private final String input = "{\"prompt\":\"生成一个待办应用\",\"intent\":\"CREATE\",\"baseVersionId\":null,\"expectedProjectRevision\":0}";
    private String project(ApiBrowser browser) throws Exception {
        browser.initialize();
        return browser.request("POST", "/api/projects", "{\"name\":\"生成任务测试\"}", UUID.randomUUID().toString()).body().path("id").asText();
    }
    @Test void admitsOncePersistsMessageAndProtectsReadsAndWrites() throws Exception {
        var browser = new ApiBrowser(port); String id = project(browser);
        String path = "/api/projects/" + id + "/runs";
        String key = UUID.randomUUID().toString();
        var queued = browser.request("POST", path, input, key);
        assertThat(queued.status()).isEqualTo(202);
        assertThat(browser.request("POST", path, input, key).body()).isEqualTo(queued.body());
        assertThat(browser.request("POST", path, input.replace("待办", "笔记"), key).status()).isEqualTo(422);
        assertThat(browser.request("POST", path, input, UUID.randomUUID().toString()).status()).isEqualTo(409);
        String runPath = "/api/runs/" + queued.body().path("runId").asText();
        assertThat(browser.get(runPath).body().path("status").asText()).isEqualTo("QUEUED");
        assertThat(browser.get("/api/projects/" + id + "/messages").body().path("items")).hasSize(1);
        assertThat(browser.get("/api/projects/" + id).body().path("activeRunId").asText()).isEqualTo(queued.body().path("runId").asText());
        var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.get(runPath).status()).isEqualTo(404);
        assertThat(stranger.get(path).status()).isEqualTo(404);
        assertThat(stranger.get("/api/projects/" + id + "/messages").status()).isEqualTo(404);
        assertThat(stranger.request("PUT", runPath + "/cancellation", "{}", null).status()).isEqualTo(404);
        assertThat(browser.request("POST", path, input, key, null).status()).isEqualTo(403);
        assertThat(browser.request("PUT", runPath + "/cancellation", "{}", null).status()).isEqualTo(200);
        assertThat(browser.get(runPath).body().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(browser.get("/api/projects/" + id).body().path("activeRunId").isNull()).isTrue();
        assertThat(browser.request("POST", path, input, key).body()).isEqualTo(queued.body());
    }
    @Test void validatesBaselineAndSerializesConcurrentSubmissions() throws Exception {
        var browser = new ApiBrowser(port); String path = "/api/projects/" + project(browser) + "/runs";
        for (String invalid : new String[]{input.replace("\"CREATE\"", "\"OTHER\""), input.replace("生成一个待办应用", " "),
                input.replace("\"prompt\":", "\"actorId\":\"spoof\",\"prompt\":"), input.replace("Revision\":0", "Revision\":-1")}) {
            assertThat(browser.request("POST", path, invalid, UUID.randomUUID().toString()).status()).isEqualTo(422);
        }
        assertThat(browser.request("POST", path, input.replace("Revision\":0", "Revision\":1"), UUID.randomUUID().toString()).status()).isEqualTo(409);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2); String key = UUID.randomUUID().toString();
            java.util.concurrent.Callable<ApiBrowser.Reply> submit = () -> { barrier.await(); return browser.request("POST", path, input, key); };
            var a = pool.submit(submit); var b = pool.submit(submit);
            var first = a.get(); var second = b.get();
            assertThat(first.status()).isEqualTo(202); assertThat(second.body()).isEqualTo(first.body());
            assertThat(browser.get(path).body().path("items")).hasSize(1);
            browser.request("PUT", "/api/runs/" + first.body().path("runId").asText() + "/cancellation", "{}", null);
        } finally { pool.shutdownNow(); }
    }
}
