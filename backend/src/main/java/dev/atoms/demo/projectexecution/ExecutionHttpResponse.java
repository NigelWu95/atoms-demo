package dev.atoms.demo.projectexecution;

import java.util.List;

/** Fully buffered, validated application bytes. No upstream cookies, redirects, policy headers or endpoint. */
public final class ExecutionHttpResponse {
    private final int status;
    private final String contentType, etag;
    private final byte[] body;
    private final List<String> applicationCookies;

    ExecutionHttpResponse(int status, String contentType, String etag, byte[] body) {
        this(status, contentType, etag, body, List.of());
    }
    ExecutionHttpResponse(int status, String contentType, String etag, byte[] body, List<String> applicationCookies) {
        this.status = status; this.contentType = contentType; this.etag = etag; this.body = body.clone();
        this.applicationCookies = List.copyOf(applicationCookies);
    }
    public int status() { return status; }
    public String contentType() { return contentType; }
    public String etag() { return etag; }
    public byte[] body() { return body.clone(); }
    public int bytes() { return body.length; }
    /** Raw upstream cookies are never emitted by iframe preview; publication must namespace them first. */
    public List<String> applicationCookies() { return applicationCookies; }
    @Override public String toString() { return "ExecutionHttpResponse[status=" + status + ", bytes=" + body.length + "]"; }
}
