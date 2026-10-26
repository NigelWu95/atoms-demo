package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Combined real PG/Git and owned Node/JVM boundaries. Controlled tools are never claimed as a native build. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionConcurrencyIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;
    ExecutionPreparationSchedulerIT fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new ExecutionPreparationSchedulerIT();
        fixture.jdbc = jdbc; fixture.projects = projects; fixture.transactions = transactions;
        fixture.database = database; fixture.temporary = temporary;
        fixture.fixture(); fixture.config.withProperty("atoms.project-execution.max-concurrent-builds", "4");
    }
    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }

    @Test void fourMixedWorkersKeepTheirOwnSourceAndReceiptsWhileOnlyACancelledSlotIsReused() throws Exception {
        var first = fixture.build(); var base = fixture.base(); var prepared = fixture.submit(base);
        var third = fixture.build(); var otherBase = fixture.base(); var other = fixture.submit(otherBase); var fifth = fixture.submit(fixture.base());
        fixture.start(); fixture.await(() -> Files.exists(fixture.buildMarker(first)) && Files.exists(fixture.marker(prepared))
                && Files.exists(fixture.buildMarker(third)) && Files.exists(fixture.marker(other)));
        assertThat(fixture.launches).hasSize(4); assertThat(fixture.marker(fifth)).doesNotExist();
        var live = List.of(fixture.buildMarker(first), fixture.marker(prepared), fixture.buildMarker(third), fixture.marker(other))
                .stream().map(this::pid).toList();
        assertThat(new HashSet<>(live)).hasSize(4);
        assertCode("EXECUTION_PROJECT_BUSY", () -> fixture.buildService.submit(fixture.owner, base.projectId(), base.versionId(), "while-preparing"));
        assertCode("EXECUTION_PROJECT_BUSY", () -> fixture.service.submit(fixture.owner, first.projectId(),
                new DependencyPreparationService.Input(first.sourceVersionId(), 1), "while-building"));
        fixture.service.cancel(fixture.owner, prepared.projectId(), prepared.id());
        fixture.await(() -> fixture.terminal(prepared) && Files.exists(fixture.marker(fifth)));
        var stopped = fixture.stops.get(prepared.id()); assertThat(stopped).isNotNull();
        var otherHandle = fixture.service.handle(other.id()); var before = fixture.preparations.get(other.projectId(), other.id());
        // Avoid the fixture's evidence collector when deliberately supplying somebody else's receipt.
        doCallRealMethod().when(fixture.edits).confirmExecutionStopped(otherHandle, stopped);
        assertCode("SOURCE_EDIT_UNAVAILABLE", () -> fixture.edits.confirmExecutionStopped(otherHandle, stopped));
        assertThat(fixture.preparations.get(other.projectId(), other.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE id=?", Boolean.class, other.editLeaseId())).isTrue();
        assertThat(pid(fixture.buildMarker(first))).isEqualTo(live.get(0));
        assertThat(pid(fixture.buildMarker(third))).isEqualTo(live.get(2)); assertThat(pid(fixture.marker(other))).isEqualTo(live.get(3));
        for (long id : List.of(live.get(0), live.get(2), live.get(3))) assertThat(ProcessHandle.of(id).orElseThrow().isAlive()).isTrue();
        assertThat(fixture.source.snapshot(fixture.owner, base.projectId(), base.versionId()).sha256()).isEqualTo(prepared.treeSha256());
        assertThat(fixture.source.snapshot(fixture.owner, otherBase.projectId(), otherBase.versionId()).sha256()).isEqualTo(other.treeSha256());
        fixture.coordinator.close();
        assertThat(fixture.closed).isTrue();
        for (var task : fixture.tasks) {
            assertThat(fixture.preparations.get(task.projectId(), task.id()).status()).isEqualTo("CANCELLED"); fixture.assertReleased(task);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots", Integer.class)).isZero();
    }
    @Test void simultaneousBuildAndPreparationReserveOnlyOneSlotAndNeverPublishAnotherSourceVersion() throws Exception {
        var base = fixture.base(); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var sourceBefore = jdbc.queryForMap("SELECT * FROM project_sources WHERE project_id=?", base.projectId());
        int versionsBefore = jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var build = executor.submit(() -> compete(ready, start, () -> {
                var result = fixture.buildService.submit(fixture.owner, base.projectId(), base.versionId(), "competing-build");
                fixture.buildTasks.add(fixture.builds.get(base.projectId(), result.id()));
            }));
            var preparation = executor.submit(() -> compete(ready, start, () -> fixture.submit(base)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(build.get(60, TimeUnit.SECONDS), preparation.get(60, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "EXECUTION_PROJECT_BUSY");
            assertThat(fixture.buildTasks.size() + fixture.tasks.size()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
            assertThat(fixture.launches).isEmpty();
            for (var task : fixture.buildTasks)
                assertThat(fixture.buildService.cancel(fixture.owner, task.projectId(), task.id()).status()).isEqualTo("CANCELLED");
            for (var task : fixture.tasks) {
                assertThat(fixture.service.cancel(fixture.owner, task.projectId(), task.id()).status()).isEqualTo("CANCELLED");
                fixture.assertReleased(task);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE project_id=?", Integer.class, base.projectId())).isZero();
            assertThat(jdbc.queryForMap("SELECT * FROM project_sources WHERE project_id=?", base.projectId())).isEqualTo(sourceBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(versionsBefore);
            assertThat(fixture.source.snapshot(fixture.owner, base.projectId(), base.versionId()).sha256())
                    .isEqualTo(fixture.tasks.isEmpty() ? fixture.buildTasks.get(0).treeSha256() : fixture.tasks.get(0).treeSha256());
        } finally {
            start.countDown(); executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }
    private String compete(CountDownLatch ready, CountDownLatch start, Runnable submission) throws InterruptedException {
        ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent submission did not start");
        try { submission.run(); return "ACCEPTED"; } catch (ApiException error) { return error.code(); }
    }
    private long pid(Path marker) { try { return Long.parseLong(Files.readString(marker)); } catch (Exception error) { throw new AssertionError(error); } }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
