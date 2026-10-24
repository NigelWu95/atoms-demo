package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real sealed ZIP and transaction boundaries; external source/DB and metadata have separate PG contracts. */
class ExecutionMigrationReviewTest {
    @TempDir Path temporary;
    final UUID owner=UUID.randomUUID(), project=UUID.randomUUID(), version=UUID.randomUUID(), buildId=UUID.randomUUID(), review=UUID.randomUUID();
    static final String HASH="a".repeat(64), HISTORY="b".repeat(64), PREFIX="atoms.project-execution.";
    final ProjectSourceReadGateway source=mock(ProjectSourceReadGateway.class);
    final ExecutionDatabaseBinding databases=mock(ExecutionDatabaseBinding.class);
    final ExecutionMigrationReviewRepository repository=mock(ExecutionMigrationReviewRepository.class);
    final TestTransactions transactions=new TestTransactions();
    final AtomicReference<String> history=new AtomicReference<>(HISTORY);
    MockEnvironment environment; ExecutionPaths paths; ExecutionArtifact artifact; ExecutionMigrationReview service;
    ProjectSourceReadGateway.Snapshot snapshot;

    @BeforeEach void fixture() throws Exception {
        environment=new MockEnvironment().withProperty(PREFIX+"enabled","true").withProperty(PREFIX+"maintainer-ids",owner.toString());
        for(String name:List.of("root","node","npm","java","maven","policy")) environment.withProperty(PREFIX+name,temporary.resolve(name).toString());
        var files=List.of(file("V001__first.sql","CREATE TABLE first_table(id int);\n"),file("V002__next.sql","ALTER TABLE first_table ADD name text;\n"));
        var tree=new ProjectSourcePolicy().validate(files);
        var build=new ExecutionBuild(buildId,project,owner,version,tree.sha256(),HASH,HASH,"SUCCEEDED",false,null,Instant.now(),Instant.now(),0);
        paths=new ExecutionPaths(temporary.toRealPath().resolve("execution"),List.of()); var task=paths.createBuild(buildId);
        Path backend=ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        Path jar=Files.createDirectory(backend.resolve("target")).resolve("app.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(var file:files) { zip.putNextEntry(new ZipEntry(ExecutionMigrations.JAR+file.path().substring(ExecutionMigrations.SOURCE.length()))); zip.write(file.content()); zip.closeEntry(); }
        }
        artifact=ExecutionArtifact.seal(paths,task,build,evidence(),budget(),()->{});
        var binding=new ExecutionBindingRepository.Target(project,UUID.randomUUID(),5432,"fixture_app","fixture_role",29101,1000,1001);
        var identity=new ExecutionMigrationReviewRepository.Identity(build,artifact.summary().jarSha256(),artifact.summary().manifestSha256(),binding);
        snapshot=new ProjectSourceReadGateway.Snapshot(new SourceVersion(version,project,1,"a".repeat(40),tree.sha256(),tree.files().size(),tree.totalBytes(),null,null,null,null,null,Instant.now()),tree);
        when(repository.identity(owner,project,buildId)).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return identity; });
        when(repository.validate(eq(owner),eq(identity),any(),any())).thenReturn(ExecutionMigrationPolicy.Decision.REVIEW_REQUIRED);
        when(repository.approve(eq(owner),eq(identity),any(),any(),anyString(),anyString())).thenReturn(review);
        when(source.read(eq(owner),eq(project),eq(version),any())).thenAnswer(call -> { assertOutside(); return snapshot; });
        when(databases.history(eq(project),any())).thenAnswer(call -> {
            assertOutside();
            return new ExecutionMigrationHistory.Snapshot(binding,new ExecutionMigrationHistory.State(true,3,1234,List.of(),history.get()));
        });
        service=new ExecutionMigrationReview(new ExecutionSettings(environment),repository,transactions,source,databases);
    }
    @AfterEach void close() throws Exception { if(paths!=null) paths.close(); }

    @Test void readsSealedArtifactAndFreshHistoryOutsideMetadataTransactions() throws Exception {
        var first=service.inspect(owner,project,buildId,artifact,budget());
        assertThat(first.jarSha256()).isEqualTo(artifact.summary().jarSha256()); assertThat(first.historySha256()).isEqualTo(HISTORY);
        assertThat(service.approve(owner,project,buildId,first.historySha256(),first.targetSha256(),artifact,budget())).isEqualTo(review);
        history.set("c".repeat(64));
        assertThat(service.inspect(owner,project,buildId,artifact,budget()).historySha256()).isEqualTo("c".repeat(64));
        assertThat(first.toString()).doesNotContain(temporary.toString(),"fixture_app","fixture_role","jdbc:");
    }
    @Test void disabledAndNonMaintainerRequestsDoNotReadInputs() {
        code(() -> service.inspect(UUID.randomUUID(),project,buildId,artifact,budget()),"EXECUTION_FORBIDDEN");
        environment.withProperty(PREFIX+"enabled","false");
        code(() -> service.inspect(owner,project,buildId,artifact,budget()),"EXECUTION_DISABLED");
        verifyNoInteractions(source,databases,repository);
    }
    @Test void revocationDuringSourceReadPreventsDatabaseInspectionAndApproval() throws Exception {
        when(source.read(eq(owner),eq(project),eq(version),any())).thenAnswer(call -> { environment.withProperty(PREFIX+"enabled","false"); return snapshot; });
        code(() -> service.approve(owner,project,buildId,HISTORY,HASH,artifact,budget()),"EXECUTION_DISABLED");
        verifyNoInteractions(databases);
    }
    @Test void revocationDuringApprovalRollsBackTheMetadataTransaction() throws Exception {
        when(repository.approve(eq(owner),any(),any(),any(),anyString(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); environment.withProperty(PREFIX+"enabled","false"); return review;
        });
        code(() -> service.approve(owner,project,buildId,HISTORY,HASH,artifact,budget()),"EXECUTION_DISABLED");
        assertThat(transactions.rollbacks).isEqualTo(1);
    }
    @Test void connectionFailuresLoseDriverDetailsAndDoNotApprove() throws Exception {
        var failure=new IOException("jdbc:private-host password=PRIVATE_FIXTURE",new IllegalStateException("private-path"));
        failure.addSuppressed(new IOException("private-account"));
        when(databases.history(eq(project),any())).thenThrow(failure);
        assertThatThrownBy(() -> service.approve(owner,project,buildId,HISTORY,HASH,artifact,budget()))
                .hasMessage("RUN_MIGRATION_CHECK_FAILED").hasNoCause().hasNoSuppressedExceptions();
        verify(repository,never()).approve(any(),any(),any(),any(),anyString(),anyString());
    }
    @Test void callerTransactionCannotEncloseFilesystemOrBusinessDatabaseIo() {
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> {
            try { return service.inspect(owner,project,buildId,artifact,budget()); }
            catch(IOException error) { throw new java.io.UncheckedIOException(error); }
        })).hasCauseInstanceOf(IOException.class).hasRootCauseMessage("EXECUTION_TRANSACTION_ACTIVE");
        verifyNoInteractions(repository,source,databases);
    }
    private SourceTree.File file(String name,String sql) { return new SourceTree.File(ExecutionMigrations.SOURCE+name,sql.getBytes(StandardCharsets.UTF_8),0100644); }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private void assertOutside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String expected) {
        assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(expected));
    }
    private ExecutionArtifact.Evidence evidence() {
        var tools=new EnumMap<ExecutionToolchain.Kind,ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for(var kind:ExecutionToolchain.Kind.values()) tools.put(kind,new ExecutionToolFiles.Summary(HASH,1,20));
        return new ExecutionArtifact.Evidence(tools,new ExecutionNpmPolicy.Verified(HASH,HASH,HASH,1),new ExecutionMavenPolicy.Verified(HASH,HASH),
                new ExecutionDiagnostics.Frontend(1,1,1),new ExecutionJavaReport.Summary(1,1),new ExecutionPackaging.Summary(1,1,HASH));
    }
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
