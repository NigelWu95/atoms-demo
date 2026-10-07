package dev.atoms.demo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.identity.GuestIdentityService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIT {
    @Autowired IdempotencyService service;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void rollsBackBusinessWriteAndClaimTogetherThenAllowsRetry() {
        UUID actor = UUID.fromString(guests.create().userId());
        UUID probe = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> service.execute(actor, "POST", "/test", key, Map.of("name", "x"), () -> {
            jdbc.update("INSERT INTO guest_users(id) VALUES (?)", probe);
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_users WHERE id = ?", Integer.class, probe)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE actor_id = ?",
                Integer.class, actor)).isZero();
        var result = service.execute(actor, "POST", "/test", key, Map.of("name", "x"),
                () -> new IdempotencyService.Result(201, json.valueToTree(Map.of("id", probe))));
        assertThat(result.status()).isEqualTo(201);
    }
    @Test
    void canonicalizesNestedObjectOrderAndScopesMethodResourceAndActor() {
        UUID actor = UUID.fromString(guests.create().userId());
        var firstBody = new LinkedHashMap<String, Object>();
        firstBody.put("b", Map.of("z", 1, "a", 2));
        firstBody.put("a", 3);
        var reply = new IdempotencyService.Result(202, json.valueToTree(Map.of("result", "original")));
        service.execute(actor, "POST", "/test", "same-key", firstBody, () -> reply);
        var replay = service.execute(actor, "POST", "/test", "same-key",
                Map.of("a", 3, "b", Map.of("a", 2, "z", 1)), () -> { throw new AssertionError("must not run"); });
        assertThat(replay).isEqualTo(reply);
        assertThat(service.execute(actor, "PUT", "/test", "same-key", Map.of(), () -> reply)).isEqualTo(reply);
        assertThat(service.execute(actor, "POST", "/another", "same-key", Map.of(), () -> reply)).isEqualTo(reply);
    }
}
