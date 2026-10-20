package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Internal command catalogue, not execution authorization. Workers must verify versions, policy and preflight. */
final class ExecutionToolchain {
    enum Kind { NODE, NPM, JAVA, MAVEN }
    enum Operation { NPM_CI, NPM_LOCK, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD, MAVEN_PACKAGE }
    record Locations(Path node, Path npm, Path java, Path maven) {}
    private final Map<Kind, Path> configured, resolved;
    private final Map<Kind, ExecutionToolFiles> identities;
    private final Path javaHome, mavenHome, launcher;

    private ExecutionToolchain(Map<Kind, Path> configured, Map<Kind, Path> resolved,
                               Map<Kind, ExecutionToolFiles> identities, Path launcher) {
        this.configured = Map.copyOf(configured); this.resolved = Map.copyOf(resolved);
        this.identities = Map.copyOf(identities); this.launcher = launcher;
        javaHome = resolved.get(Kind.JAVA).getParent().getParent();
        mavenHome = resolved.get(Kind.MAVEN).getParent().getParent();
    }

    static ExecutionToolchain capture(Locations locations, List<Path> protectedRoots, OwnedProcess.Budget budget) throws IOException {
        try {
            requireBudget(budget);
            var configured = Map.of(Kind.NODE, locations.node(), Kind.NPM, locations.npm(),
                    Kind.JAVA, locations.java(), Kind.MAVEN, locations.maven());
            var resolved = new EnumMap<Kind, Path>(Kind.class);
            var identities = new EnumMap<Kind, ExecutionToolFiles>(Kind.class);
            for (var entry : configured.entrySet()) {
                Path original = entry.getValue();
                if (!original.isAbsolute() || !original.normalize().equals(original)) throw unsupported();
                Path file = original.toRealPath(), root;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || (entry.getKey() != Kind.NPM && !Files.isExecutable(file))) throw unsupported();
                List<String> included;
                switch (entry.getKey()) {
                    case NODE -> {
                        requireName(file, "node"); root = file.getParent(); included = List.of("node");
                    }
                    case NPM -> {
                        requireName(file, "npm-cli.js"); requireName(file.getParent(), "bin");
                        root = file.getParent().getParent(); included = List.of(".");
                    }
                    case JAVA -> {
                        requireName(file, "java"); requireName(file.getParent(), "bin");
                        root = file.getParent().getParent(); included = List.of("bin", "lib", "conf", "release");
                        if (!Files.isExecutable(root.resolve("bin/javac"))) throw unsupported();
                    }
                    case MAVEN -> {
                        requireName(file, "mvn"); requireName(file.getParent(), "bin");
                        root = file.getParent().getParent(); included = List.of("bin", "boot", "lib", "conf");
                        if (!Files.isRegularFile(root.resolve("bin/m2.conf"), LinkOption.NOFOLLOW_LINKS)) throw unsupported();
                    }
                    default -> throw unsupported();
                }
                for (Path protectedRoot : protectedRoots) {
                    if (!protectedRoot.isAbsolute() || !protectedRoot.normalize().equals(protectedRoot)) throw unsupported();
                    Path protectedReal = Files.exists(protectedRoot) ? protectedRoot.toRealPath() : protectedRoot;
                    if (root.startsWith(protectedReal) || protectedReal.startsWith(root)) throw unsupported();
                }
                resolved.put(entry.getKey(), file);
                identities.put(entry.getKey(), ExecutionToolFiles.capture(root, included, budget));
            }
            Path boot = resolved.get(Kind.MAVEN).getParent().getParent().resolve("boot");
            List<Path> launchers;
            try (var entries = Files.list(boot)) {
                launchers = entries.filter(path -> path.getFileName().toString().matches("plexus-classworlds-[0-9.]+\\.jar")).toList();
            }
            // Unknown additional launchers must not be selected by filesystem ordering.
            try (var entries = Files.list(boot)) {
                if (entries.filter(path -> path.getFileName().toString().startsWith("plexus-classworlds-")).count() != 1)
                    throw unsupported();
            }
            if (launchers.size() != 1 || !Files.isRegularFile(launchers.get(0), LinkOption.NOFOLLOW_LINKS)) throw unsupported();
            var tools = new ExecutionToolchain(configured, resolved, identities, launchers.get(0));
            tools.verify(budget);
            return tools;
        } catch (IOException | RuntimeException error) {
            if ("EXECUTION_TIMEOUT".equals(error.getMessage())) throw new IOException("EXECUTION_TIMEOUT");
            throw unsupported();
        }
    }

    void verify(OwnedProcess.Budget budget) throws IOException {
        requireBudget(budget);
        try {
            for (Kind kind : Kind.values()) {
                if (!resolved.get(kind).equals(configured.get(kind).toRealPath())) throw unsupported();
                identities.get(kind).verify(budget);
            }
        } catch (IOException | RuntimeException error) {
            throw new IOException("EXECUTION_TIMEOUT".equals(error.getMessage()) ? "EXECUTION_TIMEOUT" : "EXECUTION_TOOL_CHANGED");
        }
    }
    ExecutionToolFiles.Summary identity(Kind kind) { return identities.get(kind).summary(); }
    Path javaHome() { return javaHome; }

    ExecutionProcess.Invocation probe(Kind kind, ExecutionToolHome home) throws IOException {
        return switch (kind) {
            case NODE -> invocation(Kind.NODE, List.of("--version"), home.home(), home);
            case NPM -> invocation(Kind.NODE, List.of(resolved.get(Kind.NPM).toString(), "--version"), home.home(), home);
            case JAVA -> invocation(Kind.JAVA, javaOptions(home, true), home.home(), home);
            case MAVEN -> maven(home, home.home(), true);
        };
    }

    ExecutionProcess.Invocation command(Operation operation, ExecutionToolHome home) throws IOException {
        Path frontend = home.project().resolve("frontend");
        if (operation == Operation.MAVEN_PACKAGE) return maven(home, home.project().resolve("backend"), false);
        List<String> args = switch (operation) {
            case NPM_CI -> List.of(resolved.get(Kind.NPM).toString(), "ci", "--ignore-scripts", "--no-audit", "--no-fund");
            case NPM_LOCK -> List.of(resolved.get(Kind.NPM).toString(), "install", "--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund");
            case TYPECHECK -> List.of("node_modules/vue-tsc/bin/vue-tsc.js", "--noEmit");
            case LINT -> List.of("node_modules/eslint/bin/eslint.js", "src", "dev", "vite.config.ts", "eslint.config.js");
            case UNIT_TEST -> List.of("node_modules/vitest/vitest.mjs", "run", "--reporter=json", "--outputFile=test-results/unit.json");
            case FRONTEND_BUILD -> List.of("node_modules/vite/bin/vite.js", "build");
            default -> throw unsupported();
        };
        return invocation(Kind.NODE, args, frontend, home);
    }

    private ExecutionProcess.Invocation maven(ExecutionToolHome home, Path cwd, boolean probe) throws IOException {
        var args = new ArrayList<>(javaOptions(home, false));
        if (probe) args.add("-XshowSettings:properties");
        args.addAll(List.of("-Dclassworlds.conf=" + mavenHome.resolve("bin/m2.conf"), "-Dmaven.home=" + mavenHome,
                "-Dmaven.multiModuleProjectDirectory=" + cwd, "-Dstyle.color=never",
                "-classpath", launcher.toString(), "org.codehaus.plexus.classworlds.launcher.Launcher"));
        args.addAll(home.mavenOptions());
        args.addAll(probe ? List.of("--version") : List.of("-Pfull-project-package", "verify"));
        return invocation(Kind.JAVA, args, cwd, home);
    }
    private List<String> javaOptions(ExecutionToolHome home, boolean version) {
        var args = new ArrayList<>(List.of("-Duser.home=" + home.home(), "-Djava.io.tmpdir=" + home.temporary()));
        if (version) args.addAll(List.of("-XshowSettings:properties", "--version"));
        return args;
    }
    private ExecutionProcess.Invocation invocation(Kind executable, List<String> args, Path cwd, ExecutionToolHome home) throws IOException {
        return new ExecutionProcess.Invocation(resolved.get(executable), args, cwd, home.home(), home.temporary(),
                javaHome, resolved.get(Kind.NODE).getParent());
    }
    private static void requireName(Path path, String name) throws IOException {
        if (path == null || !path.getFileName().toString().equals(name)) throw unsupported();
    }
    private static void requireBudget(OwnedProcess.Budget budget) throws IOException {
        try {
            var left = budget.remaining();
            if (left == null || left.isNegative() || left.isZero() || Thread.currentThread().isInterrupted()) throw new IOException();
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }
    private static IOException unsupported() { return new IOException("EXECUTION_TOOL_UNSUPPORTED"); }
}
