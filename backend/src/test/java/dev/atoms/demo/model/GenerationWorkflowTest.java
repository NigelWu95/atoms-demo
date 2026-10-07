package dev.atoms.demo.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenerationWorkflowTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String plan = "{\"title\":\"待办\",\"goal\":\"记录任务\",\"features\":[\"新增\"],\"preserve\":[],\"steps\":[\"实现\"],\"dataNeeded\":true}";
    private final String source = "{\"formatVersion\":1,\"manifest\":{\"title\":\"待办\",\"summary\":\"测试替身，不是真实模型\",\"runtime\":\"web-static-v1\",\"stateSchemaVersion\":1,\"initialState\":{}},\"files\":{\"view.html\":\"<main>待办</main>\",\"styles.css\":\"\",\"main.js\":\"Atoms.ready(async (sdk) => { await sdk.storage.get(); });\"}}";
    @Test void plansBuildsAndRepairsOnlyOnceWithoutBusinessData() throws Exception {
        var workflow = new GenerationWorkflow(json, new ArtifactValidator());
        var stages = new ArrayList<String>(); var plans = new ArrayList<String>();
        var result = workflow.generate("增加筛选", json.readTree(source), (stage, system, user) -> {
            stages.add(stage);
            assertThat(system).contains("JSON");
            assertThat(user).contains("增加筛选");
            return new ModelGateway.Answer(switch (stage) { case "PLANNING" -> plan; case "BUILDING" -> "{\"bad\":true}"; default -> source; }, null);
        }, value -> plans.add(value.path("goal").asText()));
        assertThat(stages).containsExactly("PLANNING", "BUILDING", "REPAIRING");
        assertThat(plans).containsExactly("记录任务");
        assertThat(result.path("files").path("main.js").asText()).contains("Atoms.ready");
    }
    @Test void doesNotRepairProviderFailuresAndRejectsPersistentInvalidOutput() {
        var workflow = new GenerationWorkflow(json, new ArtifactValidator()); var stages = new ArrayList<String>();
        assertThatThrownBy(() -> workflow.generate("test", null, (stage, system, user) -> {
            stages.add(stage); throw new ModelException("MODEL_AUTH_FAILED", "认证失败");
        }, ignored -> {})).isInstanceOf(ModelException.class).hasMessage("认证失败");
        assertThat(stages).containsExactly("PLANNING"); stages.clear();
        assertThatThrownBy(() -> workflow.generate("test", null, (stage, system, user) -> {
            stages.add(stage); return new ModelGateway.Answer(stage.equals("PLANNING") ? plan : "{}", null);
        }, ignored -> {})).isInstanceOf(ModelException.class);
        assertThat(stages).isEqualTo(List.of("PLANNING", "BUILDING", "REPAIRING"));
    }
    @Test void blankOrJsonNullOutputsAreContractErrorsEligibleForOneRepair() {
        var workflow = new GenerationWorkflow(json, new ArtifactValidator());
        for (String invalid : List.of(" ", "null")) {
            var stages = new ArrayList<String>();
            var result = workflow.generate("test", null, (stage, system, user) -> {
                stages.add(stage);
                return new ModelGateway.Answer(stage.equals("PLANNING") ? plan : stage.equals("BUILDING") ? invalid : source, null);
            }, ignored -> {});
            assertThat(result.path("formatVersion").asInt()).isEqualTo(1);
            assertThat(stages).containsExactly("PLANNING", "BUILDING", "REPAIRING");
        }
    }
    @Test void planContractErrorsIdentifyTheFieldWithoutExposingModelContent() {
        var workflow = new GenerationWorkflow(json, new ArtifactValidator());
        String invalidPlan = plan.replace("\"实现\"", "{\"private\":\"do-not-expose\"}");
        assertThatThrownBy(() -> workflow.generate("test", null,
                (stage, system, user) -> new ModelGateway.Answer(invalidPlan, null), ignored -> {}))
                .isInstanceOf(ModelException.class).hasMessageContaining("steps[0]").hasMessageNotContaining("do-not-expose");
    }
    @Test void plannerReceivesAnExplicitSixFieldExampleEvenWhenModifyingSource() throws Exception {
        var workflow = new GenerationWorkflow(json, new ArtifactValidator());
        workflow.generate("增加筛选", json.readTree(source), (stage, system, user) -> {
            if (stage.equals("PLANNING")) {
                String example = system.lines().filter(line -> line.startsWith("{\"title\":")).findFirst().orElseThrow();
                assertThat(example).contains("\"goal\":", "\"features\":[", "\"preserve\":[", "\"dataNeeded\":true", "\"steps\":[");
                assertThat(system).contains("Do not return an Artifact", "Do not omit any field");
                assertThat(user).contains("currentArtifact");
            }
            return new ModelGateway.Answer(stage.equals("PLANNING") ? plan : source, null);
        }, ignored -> {});
    }
}
