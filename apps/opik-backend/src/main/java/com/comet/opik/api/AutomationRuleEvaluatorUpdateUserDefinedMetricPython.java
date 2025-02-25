package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.util.UUID;

import static com.comet.opik.api.AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode;

@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorUpdateUserDefinedMetricPython
        extends
            AutomationRuleEvaluatorUpdate<UserDefinedMetricPythonCode> {

    @ConstructorProperties({"name", "samplingRate", "code", "projectId"})
    public AutomationRuleEvaluatorUpdateUserDefinedMetricPython(
            // TODO: add @NotNull to projectId after deprecated endpoint is removed
            @NotBlank String name, float samplingRate, @NotNull UserDefinedMetricPythonCode code, UUID projectId) {
        super(name, samplingRate, code, projectId);
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;
    }
}
