package dev.atoms.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AtomsDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtomsDemoApplication.class, args);
    }
}
