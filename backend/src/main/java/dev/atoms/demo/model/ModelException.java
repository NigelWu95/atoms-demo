package dev.atoms.demo.model;

/** Fixed safe messages only: never retain upstream bodies, prompts, or authentication headers. */
public final class ModelException extends RuntimeException {
    public record CompletionDetails(String finishReason, ModelGateway.Usage usage, Long reasoningTokens, int outputTokenLimit) {}
    private final String code;
    private final CompletionDetails completion;
    public ModelException(String code, String message) { this(code, message, null); }
    public ModelException(String code, String message, CompletionDetails completion) {
        super(message); this.code = code; this.completion = completion;
    }
    public String code() { return code; }
    public CompletionDetails completion() { return completion; }
}
