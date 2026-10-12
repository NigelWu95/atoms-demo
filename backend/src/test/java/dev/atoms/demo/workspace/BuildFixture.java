package dev.atoms.demo.workspace;

import dev.atoms.demo.AtomsDemoApplication;
import dev.atoms.fixtures.FixtureConfiguration;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Repository-only acceptance entry; never packaged into the production JAR. */
public final class BuildFixture {
    private BuildFixture() {}
    public static void main(String[] args) {
        new SpringApplicationBuilder(AtomsDemoApplication.class,FixtureConfiguration.class).profiles("test").run(arguments(args));
    }
    static String[] arguments(String[] args) {
        if(args.length!=4 || java.util.Arrays.stream(args).anyMatch(arg->!Path.of(arg).isAbsolute() || arg.chars().anyMatch(c->c<32))
                || !args[3].endsWith(".properties")) throw new IllegalArgumentException("Four explicit absolute build settings required");
        return new String[]{"--server.address=127.0.0.1","--server.port=8081","--atoms.build.enabled=true",
                "--atoms.build.root="+args[0],"--atoms.build.node="+args[1],"--atoms.build.java="+args[2],
                "--atoms.build.test-config="+args[3],"--atoms.generation.worker-enabled=false"};
    }
}
