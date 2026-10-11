package dev.atoms.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StandaloneApplication {
    public static void main(String[] args) {
        var environment = StartupConfig.load(args);
        var application = new SpringApplication(StandaloneApplication.class);
        // Preflight occurs before Spring configuration loading and database initialization.
        // https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/SpringApplication.html
        application.setEnvironment(environment);
        application.setAddCommandLineProperties(false);
        application.run();
    }
}
