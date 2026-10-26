package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionToolchainTest {
    @TempDir Path temporary;
    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }

    @Test void eachProbeChecksItsExecutableAndRuntimeDependenciesWithoutWeakeningWholeBuildVerification() throws Exception {
        for (var probe : ExecutionToolchain.Kind.values()) for (var changed : ExecutionToolchain.Kind.values()) {
            var locations = installation(Files.createDirectory(temporary.toRealPath().resolve(probe + "-" + changed)));
            var tools = ExecutionToolchain.capture(locations, List.of(), deadline());
            Path file = switch (changed) {
                case NODE -> locations.node(); case NPM -> locations.npm().toRealPath();
                case JAVA -> locations.java(); case MAVEN -> locations.maven();
            };
            Files.writeString(file, "changed-tool-identity");
            boolean needed = probe == changed || probe == ExecutionToolchain.Kind.NPM && changed == ExecutionToolchain.Kind.NODE
                    || probe == ExecutionToolchain.Kind.MAVEN && changed == ExecutionToolchain.Kind.JAVA;
            if (needed) assertThatThrownBy(() -> tools.verifyProbe(probe, deadline())).hasMessage("EXECUTION_TOOL_CHANGED");
            else tools.verifyProbe(probe, deadline());
            assertThatThrownBy(() -> tools.verify(deadline())).hasMessage("EXECUTION_TOOL_CHANGED");
            assertThatThrownBy(() -> tools.verifyProbe(probe, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
        }
    }

    @Test void unitTestUserDataStaysInPrivateTemporaryHomeWithPackageConfigurationPinned() throws Exception {
        var tools = ExecutionToolchain.capture(installation(temporary.toRealPath()), List.of(), deadline());
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var home = ExecutionToolHome.create(paths, paths.createBuild(UUID.randomUUID()), deadline());
            for (var operation : ExecutionToolchain.Operation.values()) {
                var environment = tools.command(operation, home).builder().environment();
                Path expected = operation == ExecutionToolchain.Operation.UNIT_TEST ? home.temporary() : home.home();
                assertThat(environment.get("HOME")).as(operation.name()).isEqualTo(expected.toString());
                assertThat(environment.get("JDK_JAVA_OPTIONS")).contains("\"-Duser.home=" + expected + "\"");
                assertThat(environment.get("npm_config_userconfig")).isEqualTo(home.home().resolve("npmrc").toString());
                assertThat(environment.get("npm_config_globalconfig")).isEqualTo(home.home().resolve("npm-globalrc").toString());
                assertThat(environment.get("npm_config_cache")).isEqualTo(home.home().resolve("npm-cache").toString());
            }
        }
    }

    @Test void fixedCommandsUsePinnedEntrypointsAndJvmPropertiesBeforeMainClass() throws Exception {
        var locations = installation(temporary.toRealPath());
        var tools = ExecutionToolchain.capture(locations, List.of(), deadline());
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var task = paths.createBuild(UUID.randomUUID());
            var home = ExecutionToolHome.create(paths, task, deadline());
            Files.createDirectory(paths.project(task).resolve("frontend"));
            Files.createDirectory(paths.project(task).resolve("backend"));
            var npm = tools.command(ExecutionToolchain.Operation.NPM_CI, home).builder();
            assertThat(npm.command()).containsExactly(locations.node().toString(),
                    locations.npm().toRealPath().toString(), "ci", "--ignore-scripts", "--no-audit", "--no-fund");
            assertThat(npm.directory().toPath()).isEqualTo(paths.project(task).resolve("frontend"));
            var maven = tools.command(ExecutionToolchain.Operation.MAVEN_PACKAGE, home).builder();
            assertThat(maven.command().get(0)).isEqualTo(locations.java().toString());
            assertThat(maven.command()).doesNotContain(locations.maven().toString(), "./mvnw", "-DskipTests");
            int main = maven.command().indexOf("org.codehaus.plexus.classworlds.launcher.Launcher");
            assertThat(main).isGreaterThan(0);
            assertThat(maven.command().subList(1, main)).contains("-Duser.home=" + home.home(),
                    "-Djava.io.tmpdir=" + home.temporary());
            assertThat(maven.command()).endsWith("-Pfull-project-package", "verify");
            assertThat(maven.command()).contains("-Dfull-project.resources=" + paths.directory(task).resolve("resources"));
            assertThat(maven.environment()).doesNotContainKeys("MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "NODE_OPTIONS");
            assertThat(maven.environment().get("JDK_JAVA_OPTIONS"))
                    .isEqualTo("\"-Duser.home=" + home.home() + "\" \"-Djava.io.tmpdir=" + home.temporary() + "\"");
            for (var operation : ExecutionToolchain.Operation.values()) {
                var command = tools.command(operation, home).builder().command();
                assertThat(command).doesNotContain("sh", "-c", "npm", "mvnw", "build.mjs", "--ignore-scripts=false");
                if (command.get(1).equals(locations.npm().toRealPath().toString()))
                    assertThat(command.get(2)).isIn("ci", "install"); // vitest run is not npm run.
                assertThat(command.get(0)).isEqualTo(operation == ExecutionToolchain.Operation.MAVEN_PACKAGE
                        ? locations.java().toString() : locations.node().toString());
            }
            assertThat(tools.command(ExecutionToolchain.Operation.NPM_LOCK, home).builder().command())
                    .contains("--package-lock-only", "--ignore-scripts");
            for (var kind : ExecutionToolchain.Kind.values()) {
                assertThat(tools.probe(kind, home).builder().directory().toPath()).isEqualTo(home.home());
                assertThat(tools.probe(kind, home).builder().environment().get("JDK_JAVA_OPTIONS"))
                        .isEqualTo(maven.environment().get("JDK_JAVA_OPTIONS"));
                assertThat(tools.identity(kind).sha256()).matches("[0-9a-f]{64}");
            }
            tools.verify(deadline());
            Path alias = locations.npm();
            Files.delete(alias);
            Files.createSymbolicLink(alias, locations.node());
            assertThatThrownBy(() -> tools.verify(deadline())).hasMessage("EXECUTION_TOOL_CHANGED");
        }
    }

    @Test void rejectsToolsInsideProtectedTreesAndMalformedOrIncompleteInstallations() throws Exception {
        var locations = installation(temporary.toRealPath());
        assertThatThrownBy(() -> ExecutionToolchain.capture(locations, List.of(temporary.toRealPath()), deadline()))
                .hasMessage("EXECUTION_TOOL_UNSUPPORTED").hasNoCause();
        var relative = new ExecutionToolchain.Locations(Path.of("node"), locations.npm(), locations.java(), locations.maven());
        assertThatThrownBy(() -> ExecutionToolchain.capture(relative, List.of(), deadline()))
                .hasMessage("EXECUTION_TOOL_UNSUPPORTED");
        Files.writeString(locations.maven().getParent().getParent().resolve("boot/plexus-classworlds-other.jar"), "other");
        assertThatThrownBy(() -> ExecutionToolchain.capture(locations, List.of(), deadline()))
                .hasMessage("EXECUTION_TOOL_UNSUPPORTED");
        assertThatThrownBy(() -> ExecutionToolchain.capture(locations, List.of(), () -> Duration.ZERO))
                .hasMessage("EXECUTION_TIMEOUT");
    }

    static ExecutionToolchain.Locations installation(Path root) throws Exception {
        Path node = file(root, "node/bin/node", "node");
        Path npmCli = file(root, "node/lib/node_modules/npm/bin/npm-cli.js", "npm");
        Path npm = node.resolveSibling("npm");
        Files.createSymbolicLink(npm, npmCli);
        Path java = file(root, "jdk/bin/java", "java");
        file(root, "jdk/bin/javac", "javac");
        file(root, "jdk/lib/modules", "modules");
        file(root, "jdk/conf/java.security", "security");
        file(root, "jdk/release", "release");
        Path mvn = file(root, "maven/bin/mvn", "mvn");
        file(root, "maven/bin/m2.conf", "main");
        file(root, "maven/boot/plexus-classworlds-2.9.0.jar", "boot");
        file(root, "maven/lib/maven-core-3.9.11.jar", "core");
        file(root, "maven/conf/logging/simplelogger.properties", "logging");
        return new ExecutionToolchain.Locations(node, npm, java, mvn);
    }
    private static Path file(Path root, String name, String text) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }
}
