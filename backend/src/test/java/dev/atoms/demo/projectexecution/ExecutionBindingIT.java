package dev.atoms.demo.projectexecution;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Metadata-only identity fixtures; each test rolls back. No application database is claimed or accessed. */
@ActiveProfiles("test")
@Transactional
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionBindingIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    ExecutionBindingRepository repository;

    @BeforeEach void target() {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        repository = new ExecutionBindingRepository(jdbc);
    }

    @Test void identicalRegistrationRetainsItsOriginalIdentityAndCreationTime() {
        var target = fixture(); var first = repository.reserve(target, 4);
        assertThat(repository.reserve(target, 4)).isEqualTo(first);
        assertThat(repository.get(target.project())).isEqualTo(first);
        assertThat(first.target()).isEqualTo(target);
        assertThat(first.slot()).isBetween(1, 4);
    }

    @Test void aProjectCannotSilentlyChangeItsDatabaseRolePortOrPhysicalIdentity() {
        var target = fixture(); var first = repository.reserve(target, 4);
        for (var changed : List.of(
                new ExecutionBindingRepository.Target(target.project(), UUID.randomUUID(), target.databasePort(), target.database(), target.role(), target.port(), target.databaseOid(), target.roleOid()),
                new ExecutionBindingRepository.Target(target.project(), target.id(), target.databasePort(), target.database(), target.role(), target.port()+1, target.databaseOid(), target.roleOid()),
                new ExecutionBindingRepository.Target(target.project(), target.id(), target.databasePort(), target.database(), target.role(), target.port(), target.databaseOid()+1, target.roleOid()),
                new ExecutionBindingRepository.Target(target.project(), target.id(), target.databasePort(), target.database(), target.role(), target.port(), target.databaseOid(), target.roleOid()+1))) {
            assertThatThrownBy(() -> repository.reserve(changed, 4)).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                    e -> assertThat(e.code()).isEqualTo("RUN_DB_BINDING_CONFLICT"));
            assertThat(repository.get(target.project())).isEqualTo(first);
        }
    }

    @Test void databaseConstraintsRejectCrossProjectReuseEvenWithDifferentBindingIds() {
        var one = fixture(); repository.reserve(one, 4);
        for (String dimension : List.of("id", "database", "role", "port", "databaseOid", "roleOid")) {
            var two = fixture();
            var reused = new ExecutionBindingRepository.Target(two.project(), dimension.equals("id") ? one.id() : two.id(),
                    one.databasePort(), dimension.equals("database") ? one.database() : two.database(),
                    dimension.equals("role") ? one.role() : two.role(), dimension.equals("port") ? one.port() : two.port(),
                    dimension.equals("databaseOid") ? one.databaseOid() : two.databaseOid(),
                    dimension.equals("roleOid") ? one.roleOid() : two.roleOid());
            rejected(() -> repository.reserve(reused, 4));
            assertThat(repository.get(two.project())).isNull();
        }
    }

    @Test void bindingsAreNeverDeletedOrReassignedAndDoNotContainCredentials() {
        var target = fixture(); var first = repository.reserve(target, 4);
        rejected(() -> jdbc.update("DELETE FROM execution_database_bindings WHERE project_id=?", target.project()));
        rejected(() -> jdbc.update("UPDATE execution_database_bindings SET application_port=application_port+1 WHERE project_id=?", target.project()));
        rejected(() -> jdbc.update("UPDATE execution_database_bindings SET identity_sha256=? WHERE project_id=?", "0".repeat(64), target.project()));
        assertThat(repository.get(target.project())).isEqualTo(first);
        var columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='execution_database_bindings'", String.class);
        assertThat(columns).noneMatch(c -> c.contains("password") || c.contains("url") || c.contains("path") || c.contains("secret"));
    }

    @Test void fourPersistentSlotsLimitNewBindingsWithoutEvictingExistingProjects() {
        int before = jdbc.queryForObject("SELECT count(*) FROM execution_database_bindings", Integer.class);
        for (int i = before; i < 4; i++) repository.reserve(fixture(), 4);
        var extra = fixture();
        assertThatThrownBy(() -> repository.reserve(extra, 4)).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                e -> assertThat(e.code()).isEqualTo("RUN_DB_CAPACITY_FULL"));
        assertThat(repository.get(extra.project())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_database_bindings", Integer.class)).isEqualTo(4);
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.executeWithoutResult(tx -> jdbc.update("""
                INSERT INTO execution_database_bindings(id,project_id,binding_slot,database_port,database_name,role_name,
                    application_port,database_oid,role_oid,identity_sha256) VALUES (?,?,5,?,?,?,?,?,?,?)
                """, extra.id(), extra.project(), extra.databasePort(), extra.database(), extra.role(), extra.port(),
                extra.databaseOid(), extra.roleOid(), extra.hash())))
                .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                        e -> assertThat(((java.sql.SQLException)e.getCause()).getSQLState()).isEqualTo("23514"));
    }

    @Test void lowerCapacityRejectsNewReservationsButRetainsPreviouslyBoundProjects() {
        var first = repository.reserve(fixture(), 4);
        int occupied = jdbc.queryForObject("SELECT count(*) FROM execution_database_bindings", Integer.class);
        var next = fixture();
        assertThatThrownBy(() -> repository.reserve(next, occupied)).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                e -> assertThat(e.code()).isEqualTo("RUN_DB_CAPACITY_FULL"));
        assertThat(repository.reserve(first.target(), 1)).isEqualTo(first);
        assertThat(repository.get(next.project())).isNull();
    }

    @Test @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void reservationWithoutTransactionCannotWriteAnIdentity() {
        var target = new ExecutionBindingRepository.Target(UUID.randomUUID(), UUID.randomUUID(), 5432, "not_registered",
                "not_registered_role", 29000, 120000, 120001);
        assertThatThrownBy(() -> repository.reserve(target, 4)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.get(target.project())).isNull();
    }

    private void rejected(Runnable work) {
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.executeWithoutResult(tx -> work.run())).isInstanceOf(RuntimeException.class);
    }

    private ExecutionBindingRepository.Target fixture() {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", project, owner, "binding-metadata-fixture");
        String suffix = project.toString().replace("-", "");
        int port = jdbc.queryForObject("SELECT p FROM generate_series(21000,65000) p WHERE NOT EXISTS (SELECT FROM execution_database_bindings WHERE application_port=p) ORDER BY random() LIMIT 1", Integer.class);
        long identity = (project.getLeastSignificantBits() & 0x3fffffffL) + 100000;
        return new ExecutionBindingRepository.Target(project, UUID.randomUUID(), 5432, "fixture_db_" + suffix,
                "fixture_role_" + suffix, port, identity, identity+1);
    }
}
