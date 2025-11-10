package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode;

@Builder(toBuilder = true)
public record TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId,
        String projectName,
        String name,
        Float samplingRate,
        boolean enabled,
        String filters,
        @Json TraceThreadUserDefinedMetricPythonCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<TraceThreadUserDefinedMetricPythonCode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
    }

    record TraceThreadUserDefinedMetricPythonCode(String metric) {
    }
}
