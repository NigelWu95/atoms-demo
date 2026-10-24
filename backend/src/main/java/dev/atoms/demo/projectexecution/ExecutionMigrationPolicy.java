package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.util.List;

/** Comparison only: an approval may allow an appended prefix, never repair a changed/failed history. */
final class ExecutionMigrationPolicy {
    enum Decision { INITIALIZE, UNCHANGED, REVIEW_REQUIRED }
    record Applied(int rank, String version, String description, String type, String script, Integer checksum, boolean success) {}

    static Decision evaluate(List<ExecutionMigrations.Migration> target, boolean historyExists, long objects, List<Applied> history) throws IOException {
        if (target == null || target.isEmpty() || target.size() > ExecutionMigrations.MAX_FILES || objects < 0
                || history == null || history.size() > target.size()) throw incompatible();
        int previous = 0;
        for (var migration : target) {
            if (migration == null || migration.version() <= previous) throw incompatible();
            previous = migration.version();
        }
        if (!historyExists) {
            if (objects != 0 || !history.isEmpty()) throw incompatible();
            return Decision.INITIALIZE;
        }
        if (objects < 1 || history.isEmpty()) throw incompatible();
        for (int i = 0; i < history.size(); i++) {
            var actual = history.get(i); var expected = target.get(i);
            if (actual == null || actual.rank() != i + 1 || !actual.success() || !"SQL".equals(actual.type())
                    || actual.version() == null || !actual.version().matches("[0-9]{1,9}")
                    || Integer.parseInt(actual.version()) != expected.version() || actual.checksum() == null
                    || actual.checksum() != expected.checksum() || !expected.script().equals(actual.script())
                    || !expected.description().equals(actual.description())) throw incompatible();
        }
        return history.size() == target.size() ? Decision.UNCHANGED : Decision.REVIEW_REQUIRED;
    }
    static IOException incompatible() { return new IOException("RUN_MIGRATION_INCOMPATIBLE"); }
}
