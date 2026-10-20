package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionToolHomeTest {
    @TempDir Path temporary;
    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }

    @Test void allocatesOnlyPrivateTaskScopedConfigurationAndNeverAdoptsExistingHome() throws Exception {
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var task = paths.createBuild(UUID.randomUUID());
            var config = ExecutionToolHome.create(paths, task, deadline());
            assertThat(config.home()).isEqualTo(paths.directory(task).resolve("tool-home"));
            assertThat(config.temporary()).startsWith(config.home());
            assertThat(config.mavenRepository()).startsWith(config.home());
            for (Path directory : List.of(config.home(), config.temporary(), config.mavenRepository(), config.home().resolve("npm-cache")))
                assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            for (String name : List.of("npmrc", "npm-globalrc", "settings.xml", "global-settings.xml", "toolchains.xml"))
                assertThat(Files.getPosixFilePermissions(config.home().resolve(name)))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.readString(config.home().resolve("npmrc"))).contains(
                    "registry=https://registry.npmjs.org/", "ignore-scripts=true", "strict-ssl=true", "audit=false", "fund=false");
            assertThat(Files.readString(config.home().resolve("settings.xml")))
                    .contains("<mirrorOf>*</mirrorOf>", "https://repo.maven.apache.org/maven2")
                    .doesNotContain("<servers>", "<proxies>", "<activeProfiles>");
            assertThat(config.mavenOptions()).contains("-B", "-C", "-N", "-ntp", "-itr", "-s",
                    config.home().resolve("settings.xml").toString(), "-gs", "-t", "-gt",
                    "-Dmaven.repo.local=" + config.mavenRepository(), "-Duser.home=" + config.home());
            assertThatThrownBy(() -> config.mavenOptions().add("skip-tests")).isInstanceOf(UnsupportedOperationException.class);
            config.verify(deadline());
            assertThatThrownBy(() -> ExecutionToolHome.create(paths, task, deadline()))
                    .hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
            assertThatThrownBy(() -> ExecutionToolHome.create(paths, paths.createRun(UUID.randomUUID()), deadline()))
                    .hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
            assertThatThrownBy(() -> paths.privateDirectoryKey(task, temporary))
                    .hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
            assertThatThrownBy(() -> paths.privateFileKey(task, temporary.resolve("outside"), 0))
                    .hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
        }
    }

    @Test void refusesChangedBytesExtraConfigLinksAndReplacementWithoutRepairingThem() throws Exception {
        for (String attack : List.of("bytes", "same-size-bytes", "extra", "symlink", "hardlink", "replace", "mode", "cache-link")) {
            try (var paths = new ExecutionPaths(temporary.toRealPath().resolve(attack), List.of())) {
                var config = ExecutionToolHome.create(paths, paths.createBuild(UUID.randomUUID()), deadline());
                Path file = config.home().resolve("settings.xml");
                switch (attack) {
                    case "bytes" -> Files.writeString(file, "PRIVATE_CONFIG_CANARY");
                    case "same-size-bytes" -> Files.writeString(file, Files.readString(file).replace("https://repo", "httpx://repo"));
                    case "extra" -> Files.writeString(config.home().resolve(".npmrc"), "PRIVATE_CONFIG_CANARY");
                    case "symlink" -> {
                        Files.move(file, temporary.resolve("saved-symlink"));
                        Files.createSymbolicLink(file, temporary.resolve("saved-symlink"));
                    }
                    case "hardlink" -> Files.createLink(temporary.resolve("hardlink-alias"), file);
                    case "replace" -> {
                        Files.move(file, temporary.resolve("saved-replace"));
                        Files.copy(temporary.resolve("saved-replace"), file);
                        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                    }
                    case "mode" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                    case "cache-link" -> {
                        Files.move(config.mavenRepository(), temporary.resolve("saved-cache"));
                        Files.createSymbolicLink(config.mavenRepository(), temporary.resolve("saved-cache"));
                    }
                }
                assertThatThrownBy(() -> config.verify(deadline())).hasMessage("EXECUTION_STORAGE_UNAVAILABLE")
                        .hasNoCause().hasMessageNotContaining("PRIVATE_CONFIG_CANARY");
                assertThat(Files.exists(file, LinkOption.NOFOLLOW_LINKS)).isTrue();
            }
        }
    }

    @Test void mutableCachesRemainTaskLocalAndStillCountAgainstRootCapacity() throws Exception {
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of(), 8192, 0)) {
            var config = ExecutionToolHome.create(paths, paths.createBuild(UUID.randomUUID()), deadline());
            Path cached = Files.writeString(config.mavenRepository().resolve("download"), "dependency");
            Files.writeString(config.home().resolve("npm-cache/index"), "cache entry");
            config.verify(deadline());
            Files.write(cached, new byte[8192]);
            assertThatThrownBy(() -> config.verify(deadline())).hasMessage("EXECUTION_STORAGE_CAPACITY");
            assertThat(Files.size(cached)).isEqualTo(8192); // Refusal must not erase the evidence.
        }
    }

    @Test void closingRootRevokesConfigurationAndExpiredBudgetDoesNotAllocate() throws Exception {
        var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of());
        try {
            var task = paths.createBuild(UUID.randomUUID());
            assertThatThrownBy(() -> ExecutionToolHome.create(paths, task, () -> Duration.ZERO))
                    .hasMessage("EXECUTION_TIMEOUT");
            assertThat(paths.directory(task).resolve("tool-home")).doesNotExist();
            var config = ExecutionToolHome.create(paths, task, deadline());
            paths.close();
            assertThatThrownBy(() -> config.verify(deadline())).hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
        } finally { paths.close(); }
    }
}
