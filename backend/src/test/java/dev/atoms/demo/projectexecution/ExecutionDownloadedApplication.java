package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.*;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.SourceTree;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;

/** Independent downloaded project: its own scripts, actual Maven/npm, B test/runtime config, original process ownership. */
final class ExecutionDownloadedApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path evidence, project, configuration;
    private final SourceTree expected;
    private final ExecutionPrivateConfig runtime;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build();

    ExecutionDownloadedApplication(Path parent, SourceTree expected, byte[] zip) throws Exception {
        this.expected = expected;
        evidence = Files.createTempDirectory(parent, "delivery-independent-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        project = evidence.resolve("unpacked/project");
        configuration = Path.of(System.getProperty("execution.test-config-directory")).toRealPath();
        runtime = ExecutionPrivateConfig.readBusiness(configuration.resolve(ExecutionBindingContractIT.B_DB + ".properties"), List.of(evidence));
        assertThat(runtime.applicationPort()).isEqualTo(18182);
        var names = new HashSet<String>(); expected.files().forEach(file -> names.add("project/" + file.path()));
        names.addAll(List.of("delivery/README.md", "delivery/manifest.json"));
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertThat(names.remove(entry.getName())).isTrue();
                assertThat(input.readNBytes((2 << 20) + 1).length).isLessThanOrEqualTo(2 << 20);
            }
        }
        assertThat(names).isEmpty();
        Path archive = privateFile(evidence.resolve("source.zip")); Files.write(archive, zip);
        execute(List.of("/usr/bin/unzip", "-qq", archive.toString(), "-d", evidence.resolve("unpacked").toString()), evidence, "unzip", 30);
        verifyOriginalSource();
        assertThat(Files.exists(project.resolve("backend/target"))).isFalse();
        assertThat(Files.exists(project.resolve("frontend/node_modules"))).isFalse();
    }

    void build() throws Exception {
        execute(List.of(node(), project.resolve("scripts/build.mjs").toString(), "--test-config",
                configuration.resolve("atoms_exec_b_test_20261020.properties").toString()), project, "build", 1800);
        verifyOriginalSource();
        List<byte[]> reports = new ArrayList<>();
        try (var files = Files.newDirectoryStream(project.resolve("backend/target/surefire-reports"), "TEST-*.xml")) {
            for (var file : files) reports.add(Files.readAllBytes(file));
        }
        assertThat(ExecutionJavaReport.parse(reports).tests()).isEqualTo(15);
        assertThat(Files.size(project.resolve("backend/target/app.jar"))).isGreaterThan(1);
        System.out.printf("delivery_independent_build_passed sourceTree=%s javaTests=15 evidence=%s%n", expected.sha256(), evidence);
    }
    JsonNode manifest() throws Exception { return JSON.readTree(Files.readAllBytes(evidence.resolve("unpacked/delivery/manifest.json"))); }

    List<JsonNode> rows() throws Exception {
        runtime.verify(); var result = new ArrayList<JsonNode>();
        try (var connection = DriverManager.getConnection(runtime.jdbcUrl(), runtime.inspectionProperties(5))) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var query = connection.createStatement()) {
                query.setQueryTimeout(5);
                try (var rows = query.executeQuery("SELECT row_to_json(t)::text FROM generated_app.todos t ORDER BY id")) {
                    while (rows.next()) { assertThat(result.size()).isLessThan(10000); result.add(JSON.readTree(rows.getString(1))); }
                }
            }
            connection.rollback();
        }
        return result;
    }

    JsonNode browser() throws Exception {
        var settings = Map.of("direct", true, "iframePath", "/", "marker", "downloaded-" + UUID.randomUUID(),
                "directory", evidence.toString(), "frontend", Path.of("../frontend").toRealPath().toString());
        Path input = privateFile(evidence.resolve("browser.json")); Files.write(input, JSON.writeValueAsBytes(settings));
        execute(List.of(node(), Path.of("src/test/resources/projectdelivery/preview-browser.mjs").toRealPath().toString(), input.toString()),
                evidence, "browser", 180);
        var result = JSON.readTree(Files.readAllBytes(evidence.resolve("result.json")));
        assertThat(result.path("passed").asBoolean()).isTrue(); assertThat(result.path("standalone").asBoolean()).isTrue();
        return result;
    }

    JsonNode get(long id) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18182/api/todos/" + id))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    Running start(String name) throws Exception {
        assertThat(name).isIn("first", "restarted");
        try (var probe = new java.net.Socket()) {
            assertThatThrownBy(() -> probe.connect(new InetSocketAddress("127.0.0.1", 18182), 300)).isInstanceOf(IOException.class);
        }
        return new Running(name);
    }

    private void verifyOriginalSource() throws Exception {
        for (var file : expected.files()) {
            var path = project.resolve(file.path());
            assertThat(Files.isSymbolicLink(path)).isFalse(); assertThat(Files.readAllBytes(path)).isEqualTo(file.content());
            int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            assertThat((mode & 0111) == 0 ? 0100644 : 0100755).isEqualTo(file.mode());
        }
    }

    private ProcessBuilder command(List<String> arguments, Path cwd) {
        var builder = new ProcessBuilder(arguments).directory(cwd.toFile()); var environment = builder.environment(); environment.clear();
        Path java = Path.of(System.getProperty("execution.contract-java"));
        environment.putAll(Map.of("PATH", Path.of(node()).getParent() + ":" + java.getParent() + ":/usr/bin:/bin:/usr/sbin:/sbin",
                "JAVA_HOME", java.getParent().getParent().toString(), "HOME", System.getProperty("user.home"), "LANG", "C.UTF-8", "LC_ALL", "C.UTF-8"));
        return builder;
    }

    private void execute(List<String> arguments, Path cwd, String label, int seconds) throws Exception {
        UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
        var result = new OwnedProcess().runCommand(command(arguments, cwd), new ExecutionProcess.Deadline(Duration.ofSeconds(seconds)), () -> false,
                task, execution, input -> capture(input, evidence.resolve(label + ".stdout")),
                input -> { capture(input, evidence.resolve(label + ".stderr")); return null; }, () -> {});
        assertThat(result.stopped().matches(task, execution, cwd)).isTrue();
        Files.writeString(privateFile(evidence.resolve(label + ".stopped")), result.stopped().digest());
    }

    final class Running implements AutoCloseable {
        private final UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final AtomicReference<OwnedProcess.Running> observed = new AtomicReference<>();
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final Future<OwnedProcess.Result<Long>> completion;
        private final String name;
        Running(String name) throws Exception {
            this.name = name;
            var properties = new Properties(); var carrier = new ProcessBuilder(); carrier.environment().clear(); runtime.applyRuntimeEnvironment(carrier);
            carrier.environment().forEach(properties::setProperty);
            properties.setProperty("atoms.runtime.run-id", task.toString()); properties.setProperty("atoms.runtime.execution-id", execution.toString());
            Path config = privateFile(evidence.resolve(name + "-runtime.properties"));
            try (var output = Files.newOutputStream(config)) { properties.store(output, "Private B runtime; never part of the source ZIP"); }
            var builder = command(List.of(node(), project.resolve("scripts/start.mjs").toString(), "--config", config.toString()), project);
            completion = thread.submit(() -> new OwnedProcess().runObservedCommand(builder, new ExecutionProcess.Deadline(Duration.ofMinutes(10)), stop::get,
                    task, execution, input -> capture(input, evidence.resolve(name + ".stdout")),
                    input -> { capture(input, evidence.resolve(name + ".stderr")); return null; }, runtime::verify, observed::set));
        }
        void ready() throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < until && !completion.isDone()) {
                try {
                    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18182/api/health"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    var health = JSON.readTree(response.body());
                    if (response.statusCode() == 200 && task.toString().equals(health.path("runId").asText())
                            && execution.toString().equals(health.path("executionId").asText())
                            && observed.get() != null && observed.get().isAlive() && observed.get().matches(task, execution, project)) return;
                } catch (IOException unavailable) { /* Wait only for this owned startup and exact HTTP identity. */ }
                Thread.sleep(100);
            }
            throw new AssertionError("Downloaded application did not become ready");
        }
        @Override public void close() throws Exception {
            stop.set(true); OwnedProcess.Stopped stopped;
            try {
                try { stopped = completion.get(30, TimeUnit.SECONDS).stopped(); }
                catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(OwnedProcess.Failure.class);
                    stopped = ((OwnedProcess.Failure) failure.getCause()).stopped();
                }
                assertThat(stopped).isNotNull(); assertThat(stopped.matches(task, execution, project)).isTrue();
                assertThat(observed.get()).isNotNull(); assertThat(observed.get().isAlive()).isFalse();
                Files.writeString(privateFile(evidence.resolve(name + ".stopped")), stopped.digest());
            } finally { thread.shutdown(); assertThat(thread.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
    }

    private static String node() { return System.getProperty("execution.contract-node"); }
    private static Path privateFile(Path path) throws IOException {
        return Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    private static long capture(InputStream input, Path path) throws IOException {
        long total = 0; byte[] buffer = new byte[8192]; int count;
        try (var output = Files.newOutputStream(privateFile(path))) {
            while ((count = input.read(buffer)) != -1) {
                if ((total += count) > 16 << 20) throw new IOException("EXECUTION_OUTPUT_LIMIT");
                output.write(buffer, 0, count);
            }
        }
        return total;
    }
}
