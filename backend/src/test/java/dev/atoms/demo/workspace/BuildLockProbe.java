package dev.atoms.demo.workspace;

/** Test classpath only; a separate JVM proves the OS lease is not merely an in-memory mutex. */
public class BuildLockProbe {
    public static void main(String[] args) {
        try (var lease = new BuildRootLease(new WorkspacePaths(java.nio.file.Path.of(args[0])))) {
            System.exit(0);
        } catch (java.io.IOException error) { System.exit(9); }
    }
}
