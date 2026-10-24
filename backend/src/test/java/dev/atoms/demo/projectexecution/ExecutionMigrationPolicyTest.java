package dev.atoms.demo.projectexecution;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionMigrationPolicyTest {
    private final ExecutionMigrations.Migration first = new ExecutionMigrations.Migration(1, "V001__first.sql", "first", 42, "a".repeat(64));
    private final ExecutionMigrations.Migration second = new ExecutionMigrations.Migration(2, "V002__next.sql", "next", 43, "b".repeat(64));
    private ExecutionMigrationPolicy.Applied applied(int rank, ExecutionMigrations.Migration migration) {
        return new ExecutionMigrationPolicy.Applied(rank, Integer.toString(migration.version()), migration.description(), "SQL", migration.script(), migration.checksum(), true);
    }

    @Test void onlyConfirmedEmptySchemaAllowsFirstInitialization() throws Exception {
        assertThat(ExecutionMigrationPolicy.evaluate(List.of(first), false, 0, List.of())).isEqualTo(ExecutionMigrationPolicy.Decision.INITIALIZE);
        rejected(List.of(first), false, 1, List.of());
        rejected(List.of(first), true, 1, List.of());
    }
    @Test void identicalAppliedPrefixCanRestartAndAdditionalMigrationsRequireReview() throws Exception {
        var history = List.of(applied(1, first));
        assertThat(ExecutionMigrationPolicy.evaluate(List.of(first), true, 3, history)).isEqualTo(ExecutionMigrationPolicy.Decision.UNCHANGED);
        assertThat(ExecutionMigrationPolicy.evaluate(List.of(first, second), true, 3, history)).isEqualTo(ExecutionMigrationPolicy.Decision.REVIEW_REQUIRED);
    }
    @Test void changedMissingReorderedOrFailedMigrationsCannotBeApprovedAway() {
        var original = applied(1, first);
        for (var row : List.of(
                new ExecutionMigrationPolicy.Applied(1, "1", "first", "SQL", first.script(), 99, true),
                new ExecutionMigrationPolicy.Applied(1, "1", "first", "SQL", first.script(), 42, false),
                new ExecutionMigrationPolicy.Applied(1, "1", "first", "BASELINE", first.script(), null, true),
                new ExecutionMigrationPolicy.Applied(1, null, "first", "SQL", "R__first.sql", 42, true),
                new ExecutionMigrationPolicy.Applied(1, "1", "changed", "SQL", first.script(), 42, true)))
            rejected(List.of(first, second), true, 3, List.of(row));
        rejected(List.of(first), true, 3, List.of(original, applied(2, second)));
        rejected(List.of(first, second), true, 3, List.of(applied(1, second)));
        rejected(List.of(first, second), true, 3, List.of(original, applied(3, second)));
        rejected(List.of(), false, 0, List.of());
        rejected(List.of(first, first), false, 0, List.of());
    }
    private void rejected(List<ExecutionMigrations.Migration> target, boolean exists, long objects, List<ExecutionMigrationPolicy.Applied> history) {
        assertThatThrownBy(() -> ExecutionMigrationPolicy.evaluate(target, exists, objects, history))
                .hasMessage("RUN_MIGRATION_INCOMPATIBLE").hasNoCause();
    }
}
