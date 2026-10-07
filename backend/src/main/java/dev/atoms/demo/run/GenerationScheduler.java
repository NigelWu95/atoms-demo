package dev.atoms.demo.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Local single-instance worker. HTTP disconnects do not own task lifetime. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "atoms.generation.worker-enabled", havingValue = "true", matchIfMissing = true)
public class GenerationScheduler {
    private static final Logger log = LoggerFactory.getLogger(GenerationScheduler.class);
    private final RunExecutionService execution;
    private final GenerationWorker worker;
    private volatile boolean ready;
    public GenerationScheduler(RunExecutionService execution, GenerationWorker worker) { this.execution = execution; this.worker = worker; }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        for (Run run : execution.interrupted()) execution.fail(run, "RUN_INTERRUPTED", "后端已重启，上次生成中断，未自动重发模型请求。");
        ready = true;
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000) public void tick() {
        if (!ready) return;
        try {
            for (Run run : execution.expired()) execution.fail(run, "RUN_TIMEOUT", "任务已超时，未自动重试。");
            Run queued = execution.next();
            if (queued == null) return;
            Run claimed = execution.start(queued.ownerId(), queued.id().toString());
            if (claimed != null) worker.process(claimed);
        } catch (RuntimeException unavailable) {
            log.warn("{\"event\":\"generation_tick_unavailable\",\"entryPoint\":\"scheduler\"}");
        }
    }
}
