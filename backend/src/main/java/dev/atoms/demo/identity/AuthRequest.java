package dev.atoms.demo.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

final class AuthRequest {
    private AuthRequest() {}
    static JsonNode read(HttpServletRequest request,ObjectMapper json) {
        try {
            byte[] bytes=request.getInputStream().readNBytes(4097);
            if(bytes.length>4096) throw new ApiException(413,"REQUEST_TOO_LARGE","认证请求过大。");
            JsonNode value=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
            if(value==null || !value.isObject()) throw invalid();
            return value;
        } catch(IOException failure) { throw invalid(); }
    }
    static JsonNode fields(JsonNode value,Set<String> fields) {
        if(value.size()!=fields.size()) throw invalid();
        for(String field:fields) if(!value.path(field).isTextual()) throw invalid();
        return value;
    }
    static UUID uuid(String value) {
        try { UUID id=UUID.fromString(value); if(!id.toString().equals(value)) throw invalid(); return id; }
        catch(IllegalArgumentException failure) { throw invalid(); }
    }
    static ApiException invalid() { return new ApiException(422,"INVALID_INPUT","认证请求字段无效。"); }
}
