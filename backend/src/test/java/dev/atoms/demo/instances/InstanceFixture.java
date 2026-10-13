package dev.atoms.demo.instances;

import dev.atoms.demo.AtomsDemoApplication;
import dev.atoms.fixtures.FixtureConfiguration;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Test-only real instance entry, never included in the production JAR. */
public final class InstanceFixture {
    public static void main(String[] args){
        if(args.length!=5 || java.util.Arrays.stream(args).anyMatch(value->!Path.of(value).isAbsolute() || value.contains(",") || value.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Five absolute fixture settings required");
        Path config=Path.of(args[4]);
        new SpringApplicationBuilder(AtomsDemoApplication.class,FixtureConfiguration.class).profiles("test").run(
                "--server.address=127.0.0.1","--server.port=8081","--atoms.build.enabled=true","--atoms.build.root="+args[0],
                "--atoms.build.node="+args[2],"--atoms.build.java="+args[3],"--atoms.build.test-config="+config.resolve("test.properties"),
                "--atoms.runtime.enabled=true","--atoms.runtime.root="+args[1],
                "--atoms.runtime.configs="+config.resolve("runtime-a.properties")+","+config.resolve("runtime-b.properties"),
                "--atoms.generation.worker-enabled=false");
    }
}
