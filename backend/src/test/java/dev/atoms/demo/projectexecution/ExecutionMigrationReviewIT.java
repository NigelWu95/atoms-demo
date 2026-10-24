package dev.atoms.demo.projectexecution;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** Rolled-back approval metadata fixtures. Source/JAR and business SELECT contracts are verified separately. */
@ActiveProfiles("test") @Transactional
@SpringBootTest(properties={"atoms.project-execution.enabled=false","atoms.source.enabled=false","atoms.generation.worker-enabled=false"})
class ExecutionMigrationReviewIT {
    @Autowired JdbcTemplate jdbc;
    UUID owner=UUID.randomUUID(), project=UUID.randomUUID(), source=UUID.randomUUID(), build=UUID.randomUUID();
    static final String TREE="a".repeat(64), JAR="b".repeat(64), MANIFEST="c".repeat(64), HISTORY="d".repeat(64), TARGET="e".repeat(64);
    ExecutionMigrationReviewRepository repository;
    ExecutionBindingRepository.Target binding;
    ExecutionMigrationReviewRepository.Identity identity;
    ExecutionMigrations.Target target;
    ExecutionMigrationHistory.Snapshot database;
    final ExecutionMigrations.Migration first=new ExecutionMigrations.Migration(1,"V001__first.sql","first",42,TREE);
    final ExecutionMigrations.Migration second=new ExecutionMigrations.Migration(2,"V002__next.sql","next",43,JAR);

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT current_database()||'.'||current_schema()",String.class)).isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)",owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'migration-review-metadata')",project,owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)",project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)",source,project,"a".repeat(40),TREE);
        String suffix=project.toString().replace("-","");
        binding=new ExecutionBindingRepository.Target(project,UUID.randomUUID(),5432,"review_"+suffix,"review_role_"+suffix,29101,990001,990002);
        new ExecutionBindingRepository(jdbc).reserve(binding,4);
        successfulBuild(build);
        repository=new ExecutionMigrationReviewRepository(jdbc); identity=repository.identity(owner,project,build);
        target=new ExecutionMigrations.Target(project,build,source,TREE,JAR,MANIFEST,List.of(first,second),TARGET);
        database=new ExecutionMigrationHistory.Snapshot(binding,new ExecutionMigrationHistory.State(true,3,1234,
                List.of(new ExecutionMigrationPolicy.Applied(1,"001","first","SQL",first.script(),42,true)),HISTORY));
    }
    @Test void exactReviewIsIdempotentAndRequiredBeforeAnAppendCanRun() {
        code(() -> repository.requireCompatible(owner,identity,target,database,null),"RUN_MIGRATION_REVIEW_REQUIRED");
        UUID review=repository.approve(owner,identity,target,database,HISTORY,TARGET);
        assertThat(repository.approve(owner,identity,target,database,HISTORY,TARGET)).isEqualTo(review);
        repository.requireCompatible(owner,identity,target,database,review);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT jar_sha256,history_sha256,target_sha256,approved_by FROM execution_migration_reviews WHERE id=?",review))
                .containsEntry("jar_sha256",JAR).containsEntry("history_sha256",HISTORY).containsEntry("target_sha256",TARGET).containsEntry("approved_by",owner);
    }
    @Test void changedHistoryAndPartialUpgradeInvalidatePreviousApproval() {
        UUID review=repository.approve(owner,identity,target,database,HISTORY,TARGET);
        var changed=new ExecutionMigrationHistory.Snapshot(binding,new ExecutionMigrationHistory.State(true,3,1234,database.state().applied(),"f".repeat(64)));
        code(() -> repository.approve(owner,identity,target,changed,HISTORY,TARGET),"RUN_MIGRATION_REVIEW_STALE");
        code(() -> repository.requireCompatible(owner,identity,target,changed,review),"RUN_MIGRATION_REVIEW_REQUIRED");
        var partial=new ExecutionMigrationHistory.Snapshot(binding,new ExecutionMigrationHistory.State(true,5,1234,
                List.of(database.state().applied().get(0),new ExecutionMigrationPolicy.Applied(2,"002","next","SQL",second.script(),43,true)),"f".repeat(64)));
        var next=new ExecutionMigrations.Target(project,build,source,TREE,JAR,MANIFEST,
                List.of(first,second,new ExecutionMigrations.Migration(3,"V003__last.sql","last",44,MANIFEST)),"0".repeat(64));
        code(() -> repository.requireCompatible(owner,identity,next,partial,review),"RUN_MIGRATION_REVIEW_REQUIRED");
        assertThat(count()).isEqualTo(1);
    }
    @Test void wrongBuildJarDatabaseOwnerOrProjectCannotReuseTheApproval() {
        UUID review=repository.approve(owner,identity,target,database,HISTORY,TARGET);
        var wrongJar=new ExecutionMigrations.Target(project,build,source,TREE,TREE,MANIFEST,target.migrations(),TARGET);
        code(() -> repository.approve(owner,identity,wrongJar,database,HISTORY,TARGET),"RUN_MIGRATION_INPUT_CHANGED");
        var wrongBinding=new ExecutionBindingRepository.Target(project,binding.id(),5432,binding.database(),binding.role(),29102,990001,990002);
        code(() -> repository.requireCompatible(owner,identity,target,new ExecutionMigrationHistory.Snapshot(wrongBinding,database.state()),review),"RUN_DB_BINDING_CONFLICT");
        code(() -> repository.identity(UUID.randomUUID(),project,build),"PROJECT_NOT_FOUND");
        code(() -> repository.identity(owner,UUID.randomUUID(),build),"PROJECT_NOT_FOUND");
        UUID another=UUID.randomUUID(); successfulBuild(another);
        var other=repository.identity(owner,project,another);
        var otherTarget=new ExecutionMigrations.Target(project,another,source,TREE,JAR,MANIFEST,target.migrations(),TARGET);
        code(() -> repository.requireCompatible(owner,other,otherTarget,database,review),"RUN_MIGRATION_REVIEW_REQUIRED");
        assertThat(count()).isEqualTo(1);
    }
    @Test void incompatibleOrFailedHistoryCannotBeApproved() {
        for (var row:List.of(new ExecutionMigrationPolicy.Applied(1,"001","first","SQL",first.script(),42,false),
                new ExecutionMigrationPolicy.Applied(1,"001","first","SQL",first.script(),99,true))) {
            var failed=new ExecutionMigrationHistory.Snapshot(binding,new ExecutionMigrationHistory.State(true,3,1234,List.of(row),HISTORY));
            code(() -> repository.approve(owner,identity,target,failed,HISTORY,TARGET),"RUN_MIGRATION_INCOMPATIBLE");
        }
        assertThat(count()).isZero();
    }
    @Test void emptyAndUnchangedSchemasNeedNoApprovalButArchivesAndMalformedConfirmationAreRejected() {
        var empty=new ExecutionMigrationHistory.Snapshot(binding,new ExecutionMigrationHistory.State(false,0,0,List.of(),HISTORY));
        repository.requireCompatible(owner,identity,target,empty,null);
        code(() -> repository.approve(owner,identity,target,empty,HISTORY,TARGET),"RUN_MIGRATION_REVIEW_NOT_REQUIRED");
        var unchanged=new ExecutionMigrations.Target(project,build,source,TREE,JAR,MANIFEST,List.of(first),TARGET);
        repository.requireCompatible(owner,identity,unchanged,database,null);
        code(() -> repository.approve(owner,identity,target,database,"wrong",TARGET),"RUN_INVALID_INPUT");
        jdbc.update("UPDATE projects SET archived=true WHERE id=?",project);
        code(() -> repository.approve(owner,identity,target,database,HISTORY,TARGET),"RUN_PROJECT_ARCHIVED");
        assertThat(count()).isZero();
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM execution_migration_reviews WHERE project_id=?",Integer.class,project); }
    private void code(Runnable work,String expected) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,e->assertThat(e.code()).isEqualTo(expected));
    }
    private void successfulBuild(UUID id) {
        jdbc.update("INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot) VALUES (?,?,?,?,?,?,?,1)",
                id,project,owner,source,TREE,id.toString().replace("-","").repeat(2),TREE);
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?",id);
        for(String stage:List.of("INSTALLING","CHECKING","PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?",stage,id);
        jdbc.update("UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=1,jar_sha256=?,manifest_sha256=?,worker_slot=null,finished_at=clock_timestamp() WHERE id=?",JAR,MANIFEST,id);
    }
}
