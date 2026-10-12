package dev.atoms.demo.workspace;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class BuildRepositoryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired BuildRepository builds;
    @Autowired PlatformTransactionManager transactions;
    private final String hash = "a".repeat(64);

    @Test void sourceAndFirstEventAreAtomicAndDoNotModifyTheProject() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var ids = seed();
            var build = builds.insert(ids[0], ids[1], ids[2], hash, hash);
            assertThat(build.status()).isEqualTo("QUEUED");
            assertThat(build.lastEventSeq()).isEqualTo(1);
            assertThat(builds.find(build.id()).summary()).isEqualTo(build);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM engineering_build_events WHERE build_id=?", Integer.class, build.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT project_revision FROM projects WHERE id=?", Long.class, ids[1])).isZero();
            assertThat(jdbc.queryForObject("SELECT active_run_id FROM projects WHERE id=?", UUID.class, ids[1])).isNull();
            tx.setRollbackOnly();
        });
    }
    @Test void rejectsCrossOwnerAndDuplicateActiveProjectAtTheDatabaseBoundary() {
        for (boolean crossOwner : new boolean[]{true, false}) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                var ids = seed();
                if (crossOwner) {
                    UUID stranger = UUID.randomUUID();
                    jdbc.update("INSERT INTO guest_users(id) VALUES (?)", stranger);
                    builds.insert(stranger, ids[1], ids[2], hash, hash);
                } else {
                    builds.insert(ids[0], ids[1], ids[2], hash, hash);
                    builds.insert(ids[0], ids[1], ids[2], hash, hash);
                }
            })).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
    @Test void rejectsMutationOfFrozenIdentityAndInvalidTerminalState() {
        for (String sql : new String[]{"UPDATE engineering_builds SET source_content_hash=? WHERE id=?",
                "UPDATE engineering_builds SET status=? WHERE id=?"}) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                var ids = seed();
                var build = builds.insert(ids[0], ids[1], ids[2], hash, hash);
                jdbc.update(sql, sql.contains("source_content_hash") ? "b".repeat(64) : "SUCCEEDED", build.id());
            })).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
    @Test void enforcesEventLimitAndRollsBackTheWholeTransaction() {
        UUID[] created = new UUID[1];
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var ids = seed();
            var build = builds.insert(ids[0], ids[1], ids[2], hash, hash);
            created[0] = build.id();
            jdbc.update("UPDATE engineering_builds SET last_event_seq=999 WHERE id=?", build.id());
            builds.event(build.id(), "build.stage");
            assertThat(builds.find(build.id()).summary().lastEventSeq()).isEqualTo(1000);
            builds.event(build.id(), "build.stage");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(builds.find(created[0])).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM engineering_build_events WHERE build_id=?", Integer.class, created[0])).isZero();
    }
    private UUID[] seed() {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), version = UUID.randomUUID();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'build repository fixture')", project, owner);
        jdbc.update("INSERT INTO versions(id,project_id,version_no,source_json,content_hash) VALUES (?,?,1,'{}',?)", version, project, hash);
        return new UUID[]{owner, project, version};
    }
}
