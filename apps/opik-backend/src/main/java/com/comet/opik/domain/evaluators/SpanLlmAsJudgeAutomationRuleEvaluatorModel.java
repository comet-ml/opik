package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.jdbi.v3.json.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode;

/**
 * Span LLM as Judge automation rule evaluator model.
 */
@SuperBuilder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class SpanLlmAsJudgeAutomationRuleEvaluatorModel
        extends
            AutomationRuleEvaluatorModelBase<SpanLlmAsJudgeCode>
        implements
            AutomationRuleEvaluatorModel<SpanLlmAsJudgeCode> {

    @Builder.Default
    private final SpanLlmAsJudgeCode code = null;

    /**
     * Explicit override to apply @Json annotation for JDBI serialization.
     * Lombok's @Getter doesn't preserve annotations from fields on generated methods.
     */
    @Override
    @Json
    public SpanLlmAsJudgeCode code() {
        return code;
    }

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
    }

    @Override
    public AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds) {
        return toBuilder().projectIds(projectIds).build();
    }

    /**
     * Factory method for constructing from JDBI row mapper.
     * Encapsulates model-specific construction logic including JSON parsing.
     * Uses SuperBuilder's commonFields() convenience method for DRY.
     */
    public static SpanLlmAsJudgeAutomationRuleEvaluatorModel fromRowMapper(
            AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common,
            JsonNode codeNode,
            ObjectMapper objectMapper) throws JsonProcessingException {

        return builder()
                .commonFields(common)
                .code(objectMapper.treeToValue(codeNode, SpanLlmAsJudgeCode.class))
                .build();
    }

    public record SpanLlmAsJudgeCode(LlmAsJudgeCodeParameters model,
            List<LlmAsJudgeCodeMessage> messages,
            Map<String, String> variables,
            List<LlmAsJudgeCodeSchema> schema) {
    }
}
