package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateWriteRequestTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String ID = "a".repeat(64);
    private String input(String value, String revision) {
        return "{\"loadedRuntimeId\":\"" + ID + "\",\"value\":" + value
                + ",\"expectedRevision\":" + revision + ",\"stateSchemaVersion\":1}";
    }
    @Test void canonicalSnapshotsAreImmutableAndIncludeEveryIntentField() throws Exception {
        var body = json.readTree(input("{\"z\":1,\"a\":{\"y\":2,\"x\":[null,true,\"内容\"]}}", "0"));
        var first = StateWriteRequest.parse(body, "channel:request");
        var reordered = StateWriteRequest.parse(json.readTree(input("{\"a\":{\"x\":[null,true,\"内容\"],\"y\":2},\"z\":1}", "0")), "channel:request");
        assertThat(first).isEqualTo(reordered);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("value")).put("z", 9);
        assertThat(first.valueJson()).contains("\"z\":1");
        assertThat(StateWriteRequest.parse(body, "channel:request").hash()).isNotEqualTo(first.hash());
        assertThat(StateWriteRequest.parse(json.readTree(input("{}", "1")), "k").hash())
                .isNotEqualTo(StateWriteRequest.parse(json.readTree(input("{}", "0")), "k").hash());
        assertThat(first.hash()).matches("[a-f0-9]{64}");
    }
    @Test void enforcesExactShapeIntegerBoundsAndKeyFormat() throws Exception {
        for (String revision : new String[]{"-1", "0.0", "1.5", "9007199254740991", "9223372036854775808", "\"0\"", "null"})
            invalid(input("{}", revision), "k");
        assertThat(StateWriteRequest.parse(json.readTree(input("{}", "9007199254740990")), "k").expectedRevision())
                .isEqualTo(9007199254740990L);
        for (String value : new String[]{"null", "[]", "1", "{\"x\":1e400}"}) invalid(input(value, "0"), "k");
        for (String key : new String[]{null, "", " ", "注入", "x".repeat(129), "x\n"}) invalid(input("{}", "0"), key);
        invalid(input("{}", "0").replace("\"stateSchemaVersion\":1", "\"stateSchemaVersion\":1.0"), "k");
        invalid(input("{}", "0").replace("\"stateSchemaVersion\":1", "\"stateSchemaVersion\":2"), "k");
        invalid(input("{}", "0").replace(ID, "A".repeat(64)), "k");
        invalid(input("{}", "0").replace("\"value\":{}", "\"value\":{},\"guestId\":\"attacker\""), "k");
        invalid("{}", "k");
    }
    @Test void measuresSerializedUtf8BytesAndContainerDepth() throws Exception {
        String exact = "{\"x\":\"" + "a".repeat(262136) + "\"}";
        assertThat(StateWriteRequest.parse(json.readTree(input(exact, "0")), "k").valueJson().getBytes(StandardCharsets.UTF_8))
                .hasSize(262144);
        invalid(input(exact.replace("\"x\"", "\"xx\""), "0"), "k");
        invalid(input("{\"x\":\"" + "中".repeat(90000) + "\"}", "0"), "k");
        String deep = "{\"x\":".repeat(63) + "null" + "}".repeat(63);
        assertThat(StateWriteRequest.parse(json.readTree(input(deep, "0")), "k")).isNotNull();
        invalid(input("{\"x\":" + deep + "}", "0"), "k");
    }
    private void invalid(String body, String key) {
        assertThatThrownBy(() -> StateWriteRequest.parse(json.readTree(body), key))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ApiException.Code.INVALID_INPUT));
    }
}
