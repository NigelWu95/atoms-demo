package dev.atoms.demo.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;

final class WorkbenchRequest {
    private WorkbenchRequest() {}
    static UUID owner(Principal principal) {
        if (principal == null) throw new ApiException(401, "SESSION_REQUIRED", "请重新连接账号。");
        return id(principal.getName());
    }
    static UUID id(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
        catch (IllegalArgumentException | NullPointerException error) { throw invalid(); }
    }
    static void query(HttpServletRequest request, String... allowed) {
        var names = Set.of(allowed);
        if (request.getParameterMap().entrySet().stream().anyMatch(e -> !names.contains(e.getKey()) || e.getValue().length != 1)) throw invalid();
        if (request.getMethod().equals("GET") && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) throw invalid();
    }
    static void body(JsonNode body, HttpServletRequest request, String... allowed) {
        query(request);
        var keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw invalid();
        key(keys.get(0));
        if (body == null || !body.isObject()) throw invalid();
        var names = Set.of(allowed);
        body.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw invalid(); });
    }
    static String text(JsonNode body, String name) {
        if (!body.path(name).isTextual()) throw invalid();
        return body.path(name).textValue();
    }
    static UUID id(JsonNode body, String name) { return id(text(body, name)); }
    static UUID optionalId(JsonNode body, String name) { return body.path(name).isMissingNode() || body.path(name).isNull() ? null : id(body, name); }
    static long revision(JsonNode body, String name) {
        var value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1 || value.longValue() > 9007199254740991L) throw invalid();
        return value.longValue();
    }
    static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    static ApiException invalid() { return new ApiException(422, "WORKBENCH_INVALID_INPUT", "请求参数无效，请刷新后重试。"); }
}
