package dev.atoms.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import java.util.HashSet;

/** An immutable, bounded snapshot. Object key ordering is insignificant; array ordering is not. */
record StateWriteRequest(String key, String loadedRuntimeId, long expectedRevision, String valueJson, String hash) {
    static StateWriteRequest parse(JsonNode body, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}") || body == null || !body.isObject()) throw invalid();
        var fields = new HashSet<String>();
        body.fieldNames().forEachRemaining(fields::add);
        var version = body.path("loadedRuntimeId");
        var revision = body.path("expectedRevision");
        var schema = body.path("stateSchemaVersion");
        if (!fields.equals(Set.of("loadedRuntimeId", "value", "expectedRevision", "stateSchemaVersion"))
                || !version.isTextual() || !version.textValue().matches("[a-f0-9]{64}")
                || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 0
                || revision.longValue() >= 9007199254740991L || !schema.isIntegralNumber()
                || !schema.canConvertToInt() || schema.intValue() != 1 || !body.path("value").isObject()) throw invalid();
        String value = canonical(body.path("value"), 1).toString();
        if (value.getBytes(StandardCharsets.UTF_8).length > ApplicationArtifactLoader.STATE_LIMIT) throw invalid();
        var normalized = JsonNodeFactory.instance.objectNode();
        normalized.put("loadedRuntimeId", version.textValue());
        normalized.set("value", canonical(body.path("value"), 1));
        normalized.put("expectedRevision", revision.longValue());
        normalized.put("stateSchemaVersion", 1);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8)));
            return new StateWriteRequest(key, version.textValue(), revision.longValue(), value, hash);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static JsonNode canonical(JsonNode value, int depth) {
        if (value.isContainerNode() && depth > 63) throw invalid();
        if (value.isObject()) {
            var sorted = JsonNodeFactory.instance.objectNode();
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) sorted.set(name, canonical(value.get(name), depth + 1));
            return sorted;
        }
        if (value.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            for (var item : value) array.add(canonical(item, depth + 1));
            return array;
        }
        if (value.isNumber() && !Double.isFinite(value.doubleValue())) throw invalid();
        if (!(value.isValueNode()) || value.isBinary() || value.isPojo()) throw invalid();
        return value;
    }
    private static ApiException invalid() { return new ApiException(ApiException.Code.INVALID_INPUT); }
}
