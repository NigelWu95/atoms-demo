package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deliberately hostile I/O from an owned Java fixture, never user code or a shell. */
public final class AgentChildFixture {
    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Read\",\"Glob\",\"Grep\",\"Write\",\"Edit\"]}";
    private static final String RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"permission_denials\":[]}";

    public static void main(String[] args) throws Exception {
        if (args.length > 1) publishPid(Path.of(args[1]), () -> {});
        switch (args[0]) {
            case "project-edit" -> {
                System.in.readAllBytes();
                int round = Integer.parseInt(args[2]);
                Files.writeString(Path.of("frontend/App.vue"), "<template>round-" + round + "</template>");
                Files.writeString(Path.of("backend/App.java"), "class App { int value() { return " + round + "; } }");
                Files.writeString(Path.of("backend/db/V00" + (round+1) + ".sql"), "alter table items add column value" + round + " integer;");
                System.out.println(INIT); System.out.println(RESULT);
            }
            case "invalid-source" -> {
                System.in.readAllBytes(); Files.writeString(Path.of(".env"), "PRIVATE_FIXTURE_SECRET=canary");
                System.out.println(INIT); System.out.println(RESULT);
            }
            case "source-secret", "source-symlink", "source-hardlink", "source-oversize" -> {
                System.in.readAllBytes();
                switch (args[0]) {
                    case "source-secret" -> Files.writeString(Path.of("README.md"), "api_key: public-fixture-canary-not-a-real-secret\n");
                    case "source-symlink" -> Files.createSymbolicLink(Path.of("linked.txt"), Path.of("README.md"));
                    case "source-hardlink" -> Files.createLink(Path.of("linked.txt"), Path.of("README.md"));
                    case "source-oversize" -> Files.writeString(Path.of("large.txt"), "x".repeat((2 << 20) + 1));
                    default -> throw new IllegalArgumentException("Unknown candidate fixture");
                }
                System.out.println(INIT); System.out.println(RESULT);
            }
            case "success", "exit-error" -> {
                System.in.readAllBytes();
                System.out.println(INIT); System.out.println(RESULT);
                if (args[0].equals("exit-error")) System.exit(3);
            }
            case "truncated" -> System.out.println(INIT);
            case "stdout-flood" -> { while (true) System.out.write(new byte[8192]); }
            case "stderr-flood" -> { while (true) System.err.write(new byte[8192]); }
            case "ignore-term" -> {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { Thread.sleep(60000); } catch (InterruptedException ignored) { }
                }));
                Thread.sleep(60000);
            }
            case "child-pipe" -> {
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), AgentChildFixture.class.getName(), "hang", args[2])
                        .inheritIO().start();
                Thread.sleep(500);
                System.out.println(INIT); System.out.println(RESULT);
            }
            case "hang" -> Thread.sleep(60000);
            default -> throw new IllegalArgumentException("Unknown fixture");
        }
    }

    static void publishPid(Path destination, Runnable beforePublish) throws java.io.IOException {
        Path pending = destination.resolveSibling(destination.getFileName() + ".pending");
        Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
        beforePublish.run();
        // File existence is a readiness signal to cancellation tests. Never expose a partial PID.
        Files.move(pending, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
