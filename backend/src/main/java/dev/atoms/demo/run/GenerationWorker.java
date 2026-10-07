package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.model.GenerationWorkflow;
import dev.atoms.demo.model.ModelException;
import dev.atoms.demo.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GenerationWorker {
    private static final Logger log = LoggerFactory.getLogger(GenerationWorker.class);
    private final RunExecutionService execution;
    private final GenerationWorkflow workflow;
    private final ModelGateway model;
    private final ArtifactService artifacts;
    private final ObjectMapper json;
    public GenerationWorker(RunExecutionService execution, GenerationWorkflow workflow, ModelGateway model, ArtifactService artifacts, ObjectMapper json) {
        this.execution = execution; this.workflow = workflow; this.model = model; this.artifacts = artifacts; this.json = json;
    }
    public void process(Run run) {
        try {
            var base = run.baseVersionId() == null ? null : json.valueToTree(artifacts.get(run.ownerId(), run.projectId().toString(), run.baseVersionId().toString()).artifact());
            var result = workflow.generate(run.prompt(), base, (stage, system, user) -> call(run, stage, system, user), plan -> execution.plan(run, plan));
            execution.complete(run, result);
        } catch (ModelException error) { execution.fail(run, error.code(), error.getMessage()); }
        catch (ApiException conflict) { execution.fail(run, conflict.code(), conflict.getMessage()); }
        catch (RuntimeException unexpected) { execution.fail(run, "GENERATION_FAILED", "生成未完成，请查看任务状态后再试；旧版本保持不变。"); }
    }
    private ModelGateway.Answer call(Run run, String stage, String system, String user) {
        var call = execution.reserve(run, stage);
        long start = System.nanoTime(); String outcome = "UNKNOWN";
        ModelException.CompletionDetails completion = null;
        try {
            var answer = model.complete(system, user, () -> { execution.check(run); return Thread.currentThread().isInterrupted(); });
            execution.recordUsage(call, answer.usage()); outcome = "SUCCEEDED"; return answer;
        } catch (ModelException error) {
            outcome = error.code(); completion = error.completion();
            if (completion != null) execution.recordFailedUsage(call, completion.usage());
            throw error;
        }
        finally {
            var event = json.createObjectNode().put("event", "model_call").put("entryPoint", "generation_worker")
                    .put("runId", run.id().toString()).put("callId", call.toString()).put("stage", stage)
                    .put("durationMs", (System.nanoTime() - start) / 1_000_000).put("outcome", outcome);
            if (completion != null) {
                event.put("finishReason", completion.finishReason()).put("outputTokenLimit", completion.outputTokenLimit())
                        .put("promptTokens", completion.usage().promptTokens()).put("completionTokens", completion.usage().completionTokens())
                        .put("totalTokens", completion.usage().totalTokens()).put("reasoningTokens", completion.reasoningTokens());
            }
            log.info("{}", event);
        }
    }
}
