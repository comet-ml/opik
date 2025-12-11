package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
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

    record SpanUserDefinedMetricPythonCode(String metric, Map<String, String> arguments) {
    }
}
