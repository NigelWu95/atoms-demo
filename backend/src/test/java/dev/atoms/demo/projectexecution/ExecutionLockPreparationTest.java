package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Real Node parent/children with a controlled npm CLI; no registry access or package install. */
class ExecutionLockPreparationTest {
    @TempDir Path temporary;

    @Test void rootReceiptCoversVersionProbeAndLockCommandWithIsolatedEnvironment() throws Exception {
        var locations = installation("""
                const fs=require('node:fs');
                if(process.argv.includes('--version')) { console.log('11.19.0'); }
                else { fs.writeFileSync('observed.json',JSON.stringify({args:process.argv.slice(2),cwd:process.cwd(),
                    home:process.env.HOME,config:process.env.npm_config_userconfig,keys:Object.keys(process.env)})); }
                """);
        var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(30));
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var home = ExecutionToolHome.create(paths, paths.createBuild(UUID.randomUUID()), budget);
            Path project = Files.createDirectory(temporary.toRealPath().resolve("editable source"));
            Files.createDirectory(project.resolve("frontend"));
            var tools = ExecutionToolchain.capture(locations, List.of(project), budget);
            UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
            var result = new ExecutionProcess().run(tools.lockPreparation(home, project), budget, () -> false, task, execution,
                    () -> { home.verify(budget); tools.verify(budget); });
            assertThat(result.stopped().matches(task, execution, project)).isTrue();
            var observed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(project.resolve("observed.json")));
            assertThat(observed.path("cwd").asText()).isEqualTo(project.toString());
            assertThat(observed.path("home").asText()).isEqualTo(home.home().toString());
            assertThat(observed.path("config").asText()).isEqualTo(home.home().resolve("npmrc").toString());
            assertThat(observed.path("args").toString()).contains("--package-lock-only", "--ignore-scripts", "--prefix", project.resolve("frontend").toString());
            assertThat(observed.path("keys").toString()).doesNotContain("DEEPSEEK_API_KEY", "NODE_OPTIONS", "HTTP_PROXY", "NPM_TOKEN");
        }
    }

    @Test void wrongNpmVersionCannotReachLockGeneration() throws Exception {
        var locations = installation("if(process.argv.includes('--version')) console.log('11.18.0'); else require('node:fs').writeFileSync('installed','bad');");
        var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(30));
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var home = ExecutionToolHome.create(paths, paths.createBuild(UUID.randomUUID()), budget);
            Path project = Files.createDirectory(temporary.toRealPath().resolve("editable"));
            var tools = ExecutionToolchain.capture(locations, List.of(project), budget);
            UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
            assertThatThrownBy(() -> new ExecutionProcess().run(tools.lockPreparation(home, project), budget, () -> false,
                    task, execution, () -> {})).isInstanceOfSatisfying(OwnedProcess.Failure.class,
                    error -> assertThat(error.stopped().matches(task, execution, project)).isTrue());
            assertThat(project.resolve("installed")).doesNotExist();
        }
    }

    @Test void cancellationStopsTheVersionProbeChildBeforeAnyLockCommand() throws Exception {
        var locations = installation("require('node:fs').writeFileSync('probe-started','yes'); setInterval(()=>{},1000);");
        var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(30));
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var home = ExecutionToolHome.create(paths, paths.createBuild(UUID.randomUUID()), budget);
            Path project = Files.createDirectory(temporary.toRealPath().resolve("editable"));
            var tools = ExecutionToolchain.capture(locations, List.of(project), budget);
            UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
            assertThatThrownBy(() -> new ExecutionProcess().run(tools.lockPreparation(home, project), budget,
                    () -> Files.exists(project.resolve("probe-started")), task, execution, () -> {}))
                    .isInstanceOfSatisfying(OwnedProcess.Failure.class,
                            error -> assertThat(error.stopped().matches(task, execution, project)).isTrue());
            assertThat(project.resolve("probe-started")).exists();
            assertThat(project.resolve("frontend/package-lock.json")).doesNotExist();
        }
    }

    private ExecutionToolchain.Locations installation(String npmCode) throws Exception {
        var locations = ExecutionToolchainTest.installation(temporary.toRealPath().resolve("tools"));
        Path node = Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator)).map(path -> Path.of(path).resolve("node"))
                .filter(Files::isExecutable).findFirst().orElseThrow().toRealPath();
        Files.delete(locations.node()); Files.createSymbolicLink(locations.node(), node);
        Files.writeString(locations.npm().toRealPath(), npmCode);
        return locations;
    }
}
