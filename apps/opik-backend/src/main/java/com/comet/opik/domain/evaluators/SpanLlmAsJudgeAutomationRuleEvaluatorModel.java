package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode;

@Builder(toBuilder = true)
public record SpanLlmAsJudgeAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId, // Legacy single project field for backwards compatibility
        String projectName, // Legacy project name field (resolved from projectId)
        Set<UUID> projectIds, // New multi-project field
        String name,
        Float samplingRate,
        boolean enabled,
        String filters,
        @Json SpanLlmAsJudgeCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<SpanLlmAsJudgeCode> {

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
     */
    public static SpanLlmAsJudgeAutomationRuleEvaluatorModel fromRowMapper(
            UUID id, UUID projectId, String projectName, Set<UUID> projectIds,
            String name, Float samplingRate, boolean enabled, String filters,
            JsonNode codeNode, Instant createdAt, String createdBy,
            Instant lastUpdatedAt, String lastUpdatedBy, ObjectMapper objectMapper)
            throws JsonProcessingException {

        return builder()
                .id(id)
                .projectId(projectId)
                .projectName(projectName)
                .projectIds(projectIds)
                .name(name)
                .samplingRate(samplingRate)
                .enabled(enabled)
                .filters(filters)
                .code(objectMapper.treeToValue(codeNode, SpanLlmAsJudgeCode.class))
                .createdAt(createdAt)
                .createdBy(createdBy)
                .lastUpdatedAt(lastUpdatedAt)
                .lastUpdatedBy(lastUpdatedBy)
                .build();
    }

    record SpanLlmAsJudgeCode(LlmAsJudgeCodeParameters model,
            List<LlmAsJudgeCodeMessage> messages,
            Map<String, String> variables,
            List<LlmAsJudgeCodeSchema> schema) {
    }
}
