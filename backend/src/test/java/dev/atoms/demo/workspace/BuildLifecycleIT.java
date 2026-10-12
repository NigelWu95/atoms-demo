package dev.atoms.demo.workspace;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class BuildLifecycleIT {
    @Autowired BuildRepository builds;
    @Autowired BuildLifecycle lifecycle;
    @Autowired JdbcTemplate jdbc;
    final String hash = "a".repeat(64);

    @Test void claimFreezesExecutionAndCancellationWinsAgainstLateSuccess() {
        var first = seed(); var claimed = lifecycle.claim(); seed();
        assertThat(claimed.summary().id()).isEqualTo(first.id());
        assertThat(claimed.executionPending()).isTrue();
        assertThat(lifecycle.claim()).isNull();
        lifecycle.snapshot(first.id(), claimed.executionId(), hash, hash);
        lifecycle.stage(first.id(), claimed.executionId(), "INSTALLING");
        lifecycle.stage(first.id(), claimed.executionId(), "INSTALLING");
        assertThat(builds.find(first.id()).summary().lastEventSeq()).isEqualTo(3);
        builds.requestCancellation(builds.find(first.id()).summary());
        lifecycle.finish(first.id(), claimed.executionId(), new NativeBuildProcess.Result(true, null, hash), hash);
        var result = builds.find(first.id());
        assertThat(result.summary().status()).isEqualTo("CANCELLED");
        assertThat(result.executionPending()).isFalse();
        assertThat(result.summary().loadedRuntimeId()).isNull();
        assertThat(lifecycle.claim()).isNotNull();
    }
    @Test void unknownExecutionRetainsTheFenceAndCannotBeReplayed() {
        var first = seed(); var claimed = lifecycle.claim(); seed();
        lifecycle.finish(first.id(), claimed.executionId(), NativeBuildProcess.Result.unknown(), null);
        assertThat(builds.find(first.id()).summary().status()).isEqualTo("INTERRUPTED");
        assertThat(builds.find(first.id()).executionPending()).isTrue();
        assertThat(lifecycle.claim()).isNull();
        lifecycle.releaseSettled(first.id(), UUID.randomUUID());
        assertThat(lifecycle.claim()).isNull();
        lifecycle.releaseSettled(first.id(), claimed.executionId());
        assertThat(lifecycle.claim()).isNotNull();
    }
    @Test void recoveryInterruptsQueuedAndRunningWithoutReexecutingThem() {
        var first = seed(); var claimed = lifecycle.claim(); var queued = seed();
        lifecycle.interruptPrevious();
        assertThat(builds.find(first.id()).summary().status()).isEqualTo("INTERRUPTED");
        assertThat(builds.find(queued.id()).summary().status()).isEqualTo("INTERRUPTED");
        assertThat(builds.find(first.id()).executionId()).isEqualTo(claimed.executionId());
        assertThat(builds.find(first.id()).executionPending()).isTrue();
        assertThat(lifecycle.claim()).isNull();
    }
    @Test void verifiedSuccessIsAtomicAndStaleExecutionCannotChangeIt() {
        var first = seed(); var claimed = lifecycle.claim();
        lifecycle.snapshot(first.id(), claimed.executionId(), hash, hash);
        lifecycle.finish(first.id(), UUID.randomUUID(), new NativeBuildProcess.Result(true, null, hash), hash);
        assertThat(builds.find(first.id()).summary().status()).isEqualTo("BUILDING");
        lifecycle.finish(first.id(), claimed.executionId(), new NativeBuildProcess.Result(true, null, hash), hash);
        assertThat(builds.find(first.id()).summary().status()).isEqualTo("SUCCEEDED");
        assertThat(builds.find(first.id()).summary().jarSha256()).isEqualTo(hash);
        lifecycle.finish(first.id(), claimed.executionId(), NativeBuildProcess.Result.unknown(), null);
        assertThat(builds.find(first.id()).summary().status()).isEqualTo("SUCCEEDED");
    }
    private EngineeringBuild seed() {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), version = UUID.randomUUID();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'lifecycle test')", project, owner);
        jdbc.update("INSERT INTO versions(id,project_id,version_no,source_json,content_hash) VALUES (?,?,1,'{}',?)", version, project, hash);
        return builds.insert(owner, project, version, hash, hash);
    }
}
