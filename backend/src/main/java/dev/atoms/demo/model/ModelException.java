package dev.atoms.demo.model;

/** Fixed safe messages only: never retain upstream bodies, prompts, or authentication headers. */
public final class ModelException extends RuntimeException {
    private final String code;
    public ModelException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
