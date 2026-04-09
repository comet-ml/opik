package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.infrastructure.EvalSuiteConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.ChatMessageType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps raw {@link EvaluatorItem}s from dataset items/versions into prepared evaluator
 * configurations ready for LLM-as-judge scoring.
 */
@Singleton
@Slf4j
public class EvalSuiteEvaluatorMapper {

    private final EvalSuiteConfig evalSuiteConfig;

    @Inject
    public EvalSuiteEvaluatorMapper(@NonNull EvalSuiteConfig evalSuiteConfig) {
        this.evalSuiteConfig = evalSuiteConfig;
    }

    public record PreparedEvaluator(@NonNull String name, @NonNull LlmAsJudgeCode code,
            @NonNull Map<String, String> scoreNameMapping) {
    }

    public int getEffectiveRunsPerItem(ExecutionPolicy itemPolicy, ExecutionPolicy versionPolicy) {
        if (itemPolicy != null && itemPolicy.runsPerItem() > 0) {
            return itemPolicy.runsPerItem();
        }
        if (versionPolicy != null && versionPolicy.runsPerItem() > 0) {
            return versionPolicy.runsPerItem();
        }
        return evalSuiteConfig.getDefaultRunsPerItem();
    }

    public List<PreparedEvaluator> prepareEvaluators(List<EvaluatorItem> evaluators) {
        return evaluators.stream()
                .filter(evaluator -> {
                    if (evaluator.type() != EvaluatorType.LLM_JUDGE) {
                        log.debug("Skipping non-LLM evaluator '{}' of type '{}'",
                                evaluator.name(), evaluator.type());
                        return false;
                    }
                    return true;
                })
                .flatMap(evaluator -> {
                    try {
                        LlmAsJudgeCode code = toScoringCode(evaluator.config());

                        Map<String, String> scoreNameMapping = code.schema() != null
                                ? code.schema().stream()
                                        .collect(Collectors.toMap(
                                                LlmAsJudgeOutputSchema::name,
                                                LlmAsJudgeOutputSchema::description))
                                : Map.of();

                        return Stream.of(new PreparedEvaluator(evaluator.name(), code, scoreNameMapping));
                    } catch (java.io.UncheckedIOException e) {
                        log.error("Failed to deserialize evaluator config for '{}'", evaluator.name(), e);
                        return Stream.<PreparedEvaluator>empty();
                    }
                })
                .toList();
    }

    LlmAsJudgeCode toScoringCode(JsonNode config) {
        LlmAsJudgeCode code = deserializeEvaluatorConfig(config);
        code = resolveModelName(code);
        code = renameSchemaToAssertionKeys(code);
        code = applyEvalSuitePrompt(code);
        return code;
    }

    private LlmAsJudgeCode deserializeEvaluatorConfig(JsonNode config) {
        return JsonUtils.treeToValue(config, LlmAsJudgeCode.class);
    }

    /**
     * Renames schema field names to stable identifiers (assertion_1, assertion_2, ...).
     * The original name is preserved in the description field so that scoreNameMapping
     * maps assertion_N → original assertion text. This ensures the LLM structured output
     * uses clean JSON property keys instead of full-sentence assertion descriptions.
     */
    private LlmAsJudgeCode renameSchemaToAssertionKeys(LlmAsJudgeCode code) {
        if (code.schema() == null || code.schema().isEmpty()) {
            return code;
        }

        var renamedSchema = new ArrayList<LlmAsJudgeOutputSchema>(code.schema().size());
        for (int i = 0; i < code.schema().size(); i++) {
            var original = code.schema().get(i);
            renamedSchema.add(original.toBuilder()
                    .name("assertion_%d".formatted(i + 1))
                    .description(original.name())
                    .build());
        }

        return new LlmAsJudgeCode(code.model(), code.messages(), code.variables(), renamedSchema);
    }

    /**
     * Replaces the evaluator's original messages and variables with the dedicated eval suite
     * LLM-as-judge prompt (system + user template) and formats assertions in human-readable style.
     * <p>
     * The prompt templates are defined in {@link EvalSuitePromptConstants} and mirror the Python SDK's
     * eval suite LLM judge prompts. Variables use {@code {"input": "input", "output": "output"}}
     * which map to the full trace input/output via the OnlineScoringEngine variable resolution.
     */
    private LlmAsJudgeCode applyEvalSuitePrompt(LlmAsJudgeCode code) {
        var messages = List.of(
                LlmAsJudgeMessage.builder()
                        .role(ChatMessageType.SYSTEM)
                        .content(EvalSuitePromptConstants.SYSTEM_PROMPT)
                        .build(),
                LlmAsJudgeMessage.builder()
                        .role(ChatMessageType.USER)
                        .content(EvalSuitePromptConstants.USER_MESSAGE_TEMPLATE)
                        .build());

        var variables = new HashMap<String, String>();
        variables.put("input", "input");
        variables.put("output", "output");
        variables.put("assertions", formatAssertions(code.schema()));

        return new LlmAsJudgeCode(code.model(), messages, variables, code.schema());
    }

    /**
     * Formats schema entries as a human-readable assertion list matching the SDK format:
     * {@code - `assertion_1`: assertion text}
     */
    private String formatAssertions(List<LlmAsJudgeOutputSchema> schema) {
        if (schema == null || schema.isEmpty()) {
            return "";
        }

        return schema.stream()
                .map(s -> "- `%s`: %s".formatted(s.name(), s.description()))
                .collect(Collectors.joining("\n"));
    }

    private LlmAsJudgeCode resolveModelName(LlmAsJudgeCode code) {
        var existingModel = Optional.ofNullable(code.model());
        if (existingModel.map(LlmAsJudgeModelParameters::name).filter(StringUtils::isNotBlank).isEmpty()) {
            var resolvedModel = LlmAsJudgeModelParameters.builder()
                    .name(evalSuiteConfig.getDefaultModelName())
                    .temperature(existingModel.map(LlmAsJudgeModelParameters::temperature).orElse(null))
                    .seed(existingModel.map(LlmAsJudgeModelParameters::seed).orElse(null))
                    .customParameters(existingModel.map(LlmAsJudgeModelParameters::customParameters).orElse(null))
                    .build();
            return new LlmAsJudgeCode(resolvedModel, code.messages(), code.variables(), code.schema());
        }
        return code;
    }
}
