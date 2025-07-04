package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;

@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython
        extends
            AutomationRuleEvaluator<TraceThreadUserDefinedMetricPythonCode> {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceThreadUserDefinedMetricPythonCode(
            @JsonView( {
                    View.Public.class, View.Write.class}) @NotNull String metric){

        public static final String CONTEXT_ARG_NAME = "context";
    }

    @ConstructorProperties({"id", "projectId", "projectName", "name", "samplingRate", "code", "createdAt", "createdBy",
            "lastUpdatedAt", "lastUpdatedBy"})
    public AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython(UUID id, @NotNull UUID projectId,
            String projectName,
            @NotBlank String name, float samplingRate, @NotNull TraceThreadUserDefinedMetricPythonCode code,
            Instant createdAt, String createdBy, Instant lastUpdatedAt, String lastUpdatedBy) {
        super(id, projectId, projectName, name, samplingRate, code, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
    }

    @JsonView({View.Public.class, View.Write.class})
    @Override
    public TraceThreadUserDefinedMetricPythonCode getCode() {
        return super.getCode();
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
    }

}
