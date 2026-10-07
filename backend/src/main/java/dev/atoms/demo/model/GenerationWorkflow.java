package dev.atoms.demo.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class GenerationWorkflow {
    @FunctionalInterface public interface Call { ModelGateway.Answer invoke(String stage, String system, String user); }
    private final ObjectMapper json;
    private final ArtifactValidator validator;
    private final String planner = resource("planner.md");
    private final String builder = resource("builder.md");
    public GenerationWorkflow(ObjectMapper json, ArtifactValidator validator) { this.json = json; this.validator = validator; }

    public JsonNode generate(String prompt, JsonNode base, Call call, Consumer<JsonNode> onPlan) {
        var context = json.createObjectNode().put("request", prompt);
        if (base != null) context.set("currentArtifact", base);
        JsonNode plan = parse(call.invoke("PLANNING", planner, context.toString()).content());
        validatePlan(plan); onPlan.accept(plan); context.set("plan", plan);
        String output = call.invoke("BUILDING", builder, context.toString()).content();
        try { return artifact(output); }
        catch (ApiException | ModelException invalid) {
            // One bounded repair for an actual contract failure, never for auth, rate or transport errors.
            context.put("previousOutput", output).put("validationError", invalid.getMessage());
            String repaired = call.invoke("REPAIRING", builder, context.toString()).content();
            try { return artifact(repaired); }
            catch (ApiException | ModelException again) { throw new ModelException("INVALID_ARTIFACT", "模型产物一次修复后仍未通过校验，旧版本保持不变。"); }
        }
    }
    private JsonNode artifact(String output) { JsonNode value = parse(output); validator.parse(value); return value; }
    private JsonNode parse(String output) {
        if (output == null || output.getBytes(StandardCharsets.UTF_8).length > 512 * 1024) throw invalid("输出为空或超过 512 KiB");
        try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output); }
        catch (IOException error) { throw invalid("JSON 语法或尾随内容不符合约定"); }
    }
    private void validatePlan(JsonNode plan) {
        if (plan == null || !plan.isObject()) throw invalid("规划根节点必须是对象");
        if (plan.size() != 6) throw invalid("规划必须恰好包含 6 个字段");
        if (!plan.path("dataNeeded").isBoolean()) throw invalid("dataNeeded 必须为布尔值");
        for (String field : Set.of("title", "goal")) if (!text(plan.path(field), field.equals("title") ? 120 : 400)) throw invalid(field + " 必须为限定长度的非空字符串");
        for (String field : Set.of("features", "preserve", "steps")) {
            var list = plan.path(field);
            if (!list.isArray() || list.size() > 12) throw invalid(field + " 必须为最多 12 项的数组");
            for (int i = 0; i < list.size(); i++) if (!text(list.get(i), 400)) throw invalid(field + "[" + i + "] 必须为 1–400 字符字符串");
        }
    }
    private boolean text(JsonNode value, int limit) { return value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= limit; }
    // Only schema-owned field names and fixed diagnostics are exposed, never model text.
    private ModelException invalid(String reason) { return new ModelException("MODEL_INVALID_JSON", "模型输出校验失败：" + reason + "。"); }
    private static String resource(String name) {
        try { return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Generation prompt missing"); }
    }
}
