package dev.atoms.demo.projectexecution;

/** Fully buffered, validated application bytes. No upstream cookies, redirects, policy headers or endpoint. */
public final class ExecutionHttpResponse {
    private final int status;
    private final String contentType, etag;
    private final byte[] body;

    ExecutionHttpResponse(int status, String contentType, String etag, byte[] body) {
        this.status = status; this.contentType = contentType; this.etag = etag; this.body = body.clone();
    }
    public int status() { return status; }
    public String contentType() { return contentType; }
    public String etag() { return etag; }
    public byte[] body() { return body.clone(); }
    public int bytes() { return body.length; }
    @Override public String toString() { return "ExecutionHttpResponse[status=" + status + ", bytes=" + body.length + "]"; }
}
