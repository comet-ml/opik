package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode;

@Builder(toBuilder = true)
public record SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId, // Legacy single project field for backwards compatibility
        String projectName, // Legacy project name field (resolved from projectId)
        Set<UUID> projectIds, // New multi-project field
        String name,
        Float samplingRate,
        boolean enabled,
        String filters,
        @Json SpanUserDefinedMetricPythonCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<SpanUserDefinedMetricPythonCode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON;
    }

    @Override
    public AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds) {
        return toBuilder().projectIds(projectIds).build();
    }

    /**
     * Factory method for constructing from JDBI row mapper.
     * Encapsulates model-specific construction logic including JSON parsing.
     *
     * Note: While there's duplication across the 6 model types, extracting this
     * would require reflection or complex generics. The explicit builder pattern
     * provides better type safety, performance, and maintainability.
     */
    public static SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel fromRowMapper(
            AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common,
            JsonNode codeNode,
            ObjectMapper objectMapper) throws JsonProcessingException {

        return builder()
                .id(common.id())
                .projectId(common.projectId())
                .projectName(common.projectName())
                .projectIds(common.projectIds())
                .name(common.name())
                .samplingRate(common.samplingRate())
                .enabled(common.enabled())
                .filters(common.filters())
                .code(objectMapper.treeToValue(codeNode, SpanUserDefinedMetricPythonCode.class))
                .createdAt(common.createdAt())
                .createdBy(common.createdBy())
                .lastUpdatedAt(common.lastUpdatedAt())
                .lastUpdatedBy(common.lastUpdatedBy())
                .build();
    }

    record SpanUserDefinedMetricPythonCode(String metric, Map<String, String> arguments) {
    }
}
