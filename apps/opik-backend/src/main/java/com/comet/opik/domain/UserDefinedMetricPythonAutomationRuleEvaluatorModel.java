package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.domain.UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode;

@Builder(toBuilder = true)
public record UserDefinedMetricPythonAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId,
        String projectName,
        String name,
        Float samplingRate,
        @Json UserDefinedMetricPythonCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<UserDefinedMetricPythonCode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;
    }

    record UserDefinedMetricPythonCode(String metric, Map<String, String> arguments) {
    }
}
