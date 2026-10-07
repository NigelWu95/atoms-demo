package dev.atoms.demo.model;

import java.util.function.BooleanSupplier;

public interface ModelGateway {
    record Usage(Long promptTokens, Long completionTokens, Long totalTokens) {}
    record Answer(String content, Usage usage) {}
    boolean configured();
    Answer complete(String system, String user, BooleanSupplier cancelled);
}
