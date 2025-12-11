package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.TraceThreadFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;

@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython
        extends
            AutomationRuleEvaluatorUpdate<TraceThreadUserDefinedMetricPythonCode, TraceThreadFilter> {

    @ConstructorProperties({"name", "samplingRate", "enabled", "filters", "code", "projectId", "projectIds"})
    public AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython(
            @NotBlank String name, float samplingRate, boolean enabled, List<TraceThreadFilter> filters,
            @NotNull TraceThreadUserDefinedMetricPythonCode code,
            UUID projectId,
            Set<UUID> projectIds) {
        super(name, samplingRate, enabled, filters, code, projectId, projectIds);
    }

    /**
     * Two purposes:
     * - Makes the polymorphic T code available for serialization.
     * - Provides the specific type T for Open API and Fern.
     */
    @JsonProperty
    @Override
    public List<TraceThreadFilter> getFilters() {
        return super.getFilters();
    }

    /**
     * Two purposes:
     * - Makes the polymorphic T code available for serialization.
     * - Provides the specific type T for Open API and Fern.
     */
    @JsonProperty
    @Override
    public TraceThreadUserDefinedMetricPythonCode getCode() {
        return super.getCode();
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
    }
}
