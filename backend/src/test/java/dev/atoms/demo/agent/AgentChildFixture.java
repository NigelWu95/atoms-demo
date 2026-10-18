package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deliberately hostile I/O from an owned Java fixture, never user code or a shell. */
public final class AgentChildFixture {
    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Read\",\"Glob\",\"Grep\",\"Write\",\"Edit\"]}";
    private static final String RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"permission_denials\":[]}";

    public static void main(String[] args) throws Exception {
        if (args.length > 1) Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        switch (args[0]) {
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
}
