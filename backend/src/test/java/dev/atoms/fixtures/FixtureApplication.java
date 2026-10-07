package dev.atoms.fixtures;

import dev.atoms.demo.AtomsDemoApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Explicit test-classpath entry point; never included in the production JAR. */
public class FixtureApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(AtomsDemoApplication.class, FixtureConfiguration.class)
                .profiles("test", "fixture").run(args);
    }
}
