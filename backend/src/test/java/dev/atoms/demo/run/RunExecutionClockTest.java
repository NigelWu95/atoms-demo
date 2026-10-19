package dev.atoms.demo.run;

import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.model.ModelException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunExecutionClockTest {
    @ParameterizedTest @ValueSource(strings={"2000-01-01T00:00:00Z", "2100-01-01T00:00:00Z"})
    void workerDeadlineUsesTheClockThatRecordedTheStart(String time) {
        Instant databaseNow = Instant.parse(time);
        var jdbc = mock(JdbcTemplate.class); var repository = mock(RunRepository.class);
        when(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).thenReturn(Timestamp.from(databaseNow));
        var execution = new RunExecutionService(repository, jdbc, mock(ArtifactService.class), new MockEnvironment());
        var fresh = run(databaseNow.minusSeconds(1));
        when(repository.get(fresh.ownerId(), fresh.id().toString())).thenReturn(fresh);
        assertThatCode(() -> execution.check(fresh)).doesNotThrowAnyException();
        var expired = run(databaseNow.minusSeconds(901));
        when(repository.get(expired.ownerId(), expired.id().toString())).thenReturn(expired);
        assertThatThrownBy(() -> execution.check(expired)).isInstanceOfSatisfying(ModelException.class,
                failure -> assertThat(failure.code()).isEqualTo("RUN_TIMEOUT"));
    }

    @ParameterizedTest @ValueSource(strings={"2000-01-01T00:00:00Z", "2100-01-01T00:00:00Z"})
    void schedulerAndWorkerUseTheSameDeadlineClock(String time) {
        Instant databaseNow = Instant.parse(time);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).thenReturn(Timestamp.from(databaseNow));
        var expired = run(databaseNow.minusSeconds(901));
        when(jdbc.query(anyString(), eq(RunRepository.ROW), any(Timestamp.class))).thenAnswer(call -> {
            Timestamp cutoff = call.getArgument(2);
            assertThat(cutoff.toInstant()).isEqualTo(databaseNow.minusSeconds(900));
            return java.util.List.of(expired);
        });
        var execution = new RunExecutionService(mock(RunRepository.class), jdbc, mock(ArtifactService.class), new MockEnvironment());
        assertThat(execution.expired()).containsExactly(expired);
    }

    private static Run run(Instant start) {
        return new Run(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "clock test", "CREATE", null,
                0, "RUNNING", "PLANNING", null, null, null, 1, start, start);
    }
}
