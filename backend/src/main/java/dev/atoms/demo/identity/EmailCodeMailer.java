package dev.atoms.demo.identity;

public interface EmailCodeMailer {
    void send(String recipient, String code);
}
