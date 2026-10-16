package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;

final class AuthRateLimited extends ApiException {
    private final long retryAfter;
    AuthRateLimited(long retryAfter) {
        super(429, "AUTH_RATE_LIMITED", "请求过于频繁，请稍后再试。");
        this.retryAfter = Math.max(1, retryAfter);
    }
    long retryAfter() { return retryAfter; }
}
