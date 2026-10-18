package dev.atoms.demo.source;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceObservabilityIT extends SourceIntegrationSupport {
    @Autowired DataSource dataSource;

    @Test void failureEventsExposeIdentifiersAndStableCodesNotRawSecretsOrPaths() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(SourceCoordinator.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        String canary = "NOT_REAL_SOURCE_LOG_SECRET";
        try {
            SourceOperation result;
            try (var coordinator = new SourceCoordinator(service, repository, environment, dataSource,
                    stage -> { throw new IllegalStateException(canary + " " + temporary); })) {
                coordinator.start();
                result = coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "log-failure")
                        .completion().get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
                assertThat(repository.state(project).versionId()).isNull();
            }
            var events = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(events).anySatisfy(event -> assertThat(event).contains("source_operation_finished",
                    "source_coordinator", result.id().toString(), project.toString(), "durationMs", "SOURCE_EXECUTION_UNCERTAIN"));
            assertThat(events).allSatisfy(event -> assertThat(event).doesNotContain(canary, temporary.toString(), "class Task"));
            assertThat(captured.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(captured); captured.stop();
        }
    }
}
