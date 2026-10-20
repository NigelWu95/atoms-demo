package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionToolProbeTest {
    @TempDir Path temporary;
    @Test void acceptsOnlyPinnedReleaseFamiliesAndNeverReturnsRawOutput() throws Exception {
        assertThat(ExecutionToolProbe.version(ExecutionToolchain.Kind.NODE, "v24.20.0\n")).isEqualTo("24.20.0");
        assertThat(ExecutionToolProbe.version(ExecutionToolchain.Kind.NPM, "11.19.0\n")).isEqualTo("11.19.0");
        assertThat(ExecutionToolProbe.version(ExecutionToolchain.Kind.JAVA,
                "java 17.0.5 2022-10-18 LTS\nJava(TM) SE Runtime Environment\n")).isEqualTo("17.0.5");
        assertThat(ExecutionToolProbe.version(ExecutionToolchain.Kind.MAVEN,
                "Apache Maven 3.9.11 (revision)\nMaven home: PRIVATE_PATH_CANARY\n")).isEqualTo("3.9.11");
        for (String bad : List.of("v24.19.0\n", "v24.20.0-rc.1\n", "24.20.0", "v24.20.0\nPRIVATE_CANARY",
                "v24.20.0\nv24.20.0\n", "v24.20.0\u0000", "v24.20.0\u001b[0m"))
            assertThatThrownBy(() -> ExecutionToolProbe.version(ExecutionToolchain.Kind.NODE, bad))
                    .hasMessage("EXECUTION_REPORT_INVALID").hasNoCause();
        for (var sample : List.of(
                new String[]{"NPM", "11.20.0\n"}, new String[]{"JAVA", "openjdk 21.0.1\n"},
                new String[]{"JAVA", "java 17-ea\n"}, new String[]{"MAVEN", "Apache Maven 3.9.12\n"}))
            assertThatThrownBy(() -> ExecutionToolProbe.version(ExecutionToolchain.Kind.valueOf(sample[0]), sample[1]))
                    .hasMessage("EXECUTION_REPORT_INVALID");
    }

    @Test void boundsAndValidatesEncodingAndActualJvmProperties() throws Exception {
        assertThat(ExecutionToolProbe.read(new ByteArrayInputStream("11.19.0\n".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("11.19.0\n");
        assertThatThrownBy(() -> ExecutionToolProbe.read(new ByteArrayInputStream(new byte[16385])))
                .hasMessage("EXECUTION_OUTPUT_LIMIT");
        assertThatThrownBy(() -> ExecutionToolProbe.read(new ByteArrayInputStream(new byte[]{(byte) 0xff})))
                .hasMessage("EXECUTION_REPORT_INVALID");
        Path home = Path.of("/private/task/home"), tmp = home.resolve("tmp"), java = Path.of("/jdk");
        String properties = "Property settings:\n    user.home = " + home + "\n    java.io.tmpdir = " + tmp
                + "\n    java.home = " + java + "\n";
        ExecutionToolProbe.jvmSettings(properties, home, tmp, java);
        for (String bad : List.of("", properties.replace(home.toString(), "/PRIVATE_CANARY"),
                properties + "    user.home = /unexpected\n", properties.replace("java.home", "other.home")))
            assertThatThrownBy(() -> ExecutionToolProbe.jvmSettings(bad, home, tmp, java))
                    .hasMessage("EXECUTION_REPORT_INVALID").hasNoCause();
    }

    @Test void rejectedVersionCancellationAndDriftKeepOwnedStopEvidence() throws Exception {
        var locations = ExecutionToolchainTest.installation(temporary.toRealPath());
        Files.writeString(locations.node(), "#!/bin/sh\nprintf 'v25.0.0\\n'\n");
        var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(30));
        var tools = ExecutionToolchain.capture(locations, List.of(), budget);
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            UUID task = UUID.randomUUID();
            var home = ExecutionToolHome.create(paths, paths.createBuild(task), budget);
            for (String expected : List.of("EXECUTION_REPORT_INVALID", "EXECUTION_CANCELLED",
                    "EXECUTION_STORAGE_UNAVAILABLE", "EXECUTION_TOOL_CHANGED")) {
                if (expected.equals("EXECUTION_STORAGE_UNAVAILABLE"))
                    Files.writeString(home.home().resolve("npmrc"), "PRIVATE_CONFIG_CANARY");
                if (expected.equals("EXECUTION_TOOL_CHANGED")) Files.writeString(locations.node(), "changed");
                UUID execution = UUID.randomUUID();
                assertThatThrownBy(() -> new ExecutionToolProbe().run(tools, ExecutionToolchain.Kind.NODE, home, budget,
                        () -> expected.equals("EXECUTION_CANCELLED"), task, execution))
                        .isInstanceOfSatisfying(OwnedProcess.Failure.class, failure -> {
                            assertThat(failure.getMessage()).isEqualTo(expected);
                            assertThat(failure.getMessage()).doesNotContain("PRIVATE_CONFIG_CANARY", home.home().toString());
                            assertThat(failure.stopped()).isNotNull();
                            assertThat(failure.stopped().matches(task, execution, home.home())).isTrue();
                        });
            }
        }
    }

    /** Explicit manual no-download contract; never silently runs or skips in the default unit suite. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected node, npm, java, maven absolute locations");
        Path root = Files.createTempDirectory("atoms-tool-probe-").toRealPath().resolve("execution");
        var deadline = new ExecutionProcess.Deadline(Duration.ofSeconds(180));
        var tools = ExecutionToolchain.capture(new ExecutionToolchain.Locations(
                Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3])), List.of(root), deadline);
        try (var paths = new ExecutionPaths(root, List.of())) {
            UUID task = UUID.randomUUID();
            var home = ExecutionToolHome.create(paths, paths.createBuild(task), deadline);
            for (var kind : ExecutionToolchain.Kind.values()) {
                UUID execution = UUID.randomUUID();
                var result = new ExecutionToolProbe().run(tools, kind, home, deadline, () -> false, task, execution);
                if (!result.stopped().matches(task, execution, home.home())) throw new AssertionError("Missing stop evidence");
                System.out.println(kind + " " + result.summary().version() + " " + result.summary().identity().sha256());
            }
            home.verify(deadline);
            tools.verify(deadline);
            System.out.println("PRIVATE_HOME_AND_TOOL_IDENTITY_OK");
        }
    }
}
