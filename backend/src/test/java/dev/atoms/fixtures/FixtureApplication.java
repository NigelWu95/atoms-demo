package dev.atoms.fixtures;

import dev.atoms.demo.AtomsDemoApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Explicit test-classpath entry point; never included in the production JAR. */
public class FixtureApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(AtomsDemoApplication.class, FixtureConfiguration.class)
                .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("isolated-test-email", java.util.Map.of(
                                "EMAIL_AUTH_ENABLED", "true", "SMTP_USERNAME", "fixture@qq.com", "SMTP_PASSWORD", "test-only",
                                "EMAIL_CODE_HMAC_KEY", java.util.UUID.randomUUID().toString().replace("-", "").repeat(2),
                                "EMAIL_MAX_SENDS_PER_HOUR", "1000", "EMAIL_MAX_SENDS_PER_DAY", "10000", "EMAIL_MAX_SENDS_PER_IP", "100"))))
                .profiles("test", "fixture").run(args);
    }
}
