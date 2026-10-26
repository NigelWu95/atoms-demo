package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Bounded version/configuration evidence; caller owns durable launch intent before invoking run. */
final class ExecutionToolProbe {
    record Version(ExecutionToolchain.Kind tool, String version, ExecutionToolFiles.Summary identity) {}

    OwnedProcess.Result<Version> run(ExecutionToolchain tools, ExecutionToolchain.Kind kind, ExecutionToolHome home,
                                    OwnedProcess.Budget taskBudget, BooleanSupplier cancelled, UUID task, UUID execution) throws IOException {
        return run(tools, kind, home, taskBudget, cancelled, task, execution, () -> {});
    }

    /** Internal worker hook for current authorization/source/policy checks; not a user-supplied command. */
    OwnedProcess.Result<Version> run(ExecutionToolchain tools, ExecutionToolchain.Kind kind, ExecutionToolHome home,
                                    OwnedProcess.Budget taskBudget, BooleanSupplier cancelled, UUID task, UUID execution,
                                    OwnedProcess.Preflight inputs) throws IOException {
        var probeDeadline = new ExecutionProcess.Deadline(Duration.ofSeconds(30));
        OwnedProcess.Budget budget = () -> {
            Duration total = taskBudget.remaining(), probe = probeDeadline.remaining();
            return total.compareTo(probe) < 0 ? total : probe;
        };
        return new OwnedProcess().runCommand(tools.probe(kind, home).builder(), budget, cancelled, task, execution,
                stream -> new Version(kind, version(kind, read(stream)), tools.identity(kind)), stream -> {
                    String text = read(stream);
                    if (kind == ExecutionToolchain.Kind.JAVA || kind == ExecutionToolchain.Kind.MAVEN)
                        jvmSettings(text, home.home(), home.temporary(), tools.javaHome());
                    return null;
                }, () -> {
                    tools.verifyProbe(kind, budget); home.verify(budget);
                    // Keep this inside OwnedProcess: a refusal must carry its positive no-process proof.
                    // OwnedProcess checks cancellation and the same budget again before builder.start().
                    inputs.check();
                });
    }

    static String read(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(16385);
        if (bytes.length > 16384) throw new IOException("EXECUTION_OUTPUT_LIMIT");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.chars().anyMatch(c -> c == 127 || (c < 32 && c != '\n' && c != '\r' && c != '\t'))) throw invalid();
            return text.replace("\r\n", "\n");
        } catch (java.nio.charset.CharacterCodingException error) { throw invalid(); }
    }

    static String version(ExecutionToolchain.Kind kind, String text) throws IOException {
        String expression = switch (kind) {
            case NODE -> "v(24\\.20\\.[0-9]{1,4})\\n?";
            case NPM -> "(11\\.19\\.[0-9]{1,4})\\n?";
            case JAVA -> "(?:java|openjdk) (17\\.0\\.[0-9]{1,4}(?:\\.[0-9]{1,4})?)(?: [0-9]{4}-[0-9]{2}-[0-9]{2})?(?: LTS)?";
            case MAVEN -> "Apache Maven (3\\.9\\.11)(?: \\([a-zA-Z0-9 ._-]{1,120}\\))?";
        };
        String versionLine = kind == ExecutionToolchain.Kind.NODE || kind == ExecutionToolchain.Kind.NPM
                ? text : text.lines().findFirst().orElse("");
        var matcher = Pattern.compile(expression).matcher(versionLine);
        if (!matcher.matches()) throw invalid();
        return matcher.group(1);
    }

    static void jvmSettings(String text, Path home, Path temporary, Path javaHome) throws IOException {
        var required = Map.of("user.home", home.toString(), "java.io.tmpdir", temporary.toString(), "java.home", javaHome.toString());
        for (var entry : required.entrySet()) {
            var values = text.lines().map(String::strip).filter(line -> line.startsWith(entry.getKey() + " = ")).toList();
            if (values.size() != 1 || !values.get(0).equals(entry.getKey() + " = " + entry.getValue())) throw invalid();
        }
    }
    private static IOException invalid() { return new IOException("EXECUTION_REPORT_INVALID"); }
}
