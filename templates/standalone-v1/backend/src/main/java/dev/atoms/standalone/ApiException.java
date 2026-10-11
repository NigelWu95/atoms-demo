package dev.atoms.standalone;

/** Only allowlisted messages may cross the API boundary; no upstream exception text. */
final class ApiException extends RuntimeException {
    private final Code code;
    ApiException(Code code) { super(code.message()); this.code = code; }
    Code code() { return code; }

    enum Code {
        SESSION_REQUIRED(401, "访客会话已失效，请重新连接。"),
        CSRF_REJECTED(403, "安全验证失败，请重新连接后重试。"),
        FORBIDDEN(403, "不允许访问此资源。"),
        NOT_FOUND(404, "接口不存在。"),
        METHOD_NOT_ALLOWED(405, "不支持此请求方法。"),
        STATE_CONFLICT(409, "数据已更新，请重新读取后再修改。"),
        STALE_APP_VERSION(409, "应用版本已更新，请重新加载。"),
        IDEMPOTENCY_PAYLOAD_MISMATCH(409, "同一请求标识不能用于不同内容。"),
        PAYLOAD_TOO_LARGE(413, "请求体超过大小限制。"),
        INVALID_INPUT(422, "请求格式或内容不正确。"),
        INTERNAL_ERROR(500, "服务处理失败，请稍后重试。"),
        SERVICE_UNAVAILABLE(503, "服务暂不可用，请稍后重试。");

        private final int status;
        private final String message;
        Code(int status, String message) { this.status = status; this.message = message; }
        int status() { return status; }
        String message() { return message; }
        boolean retryable() { return status >= 500; }
    }
}
