package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.TraceThreadFilter;
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
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;

@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython
        extends
            AutomationRuleEvaluator<TraceThreadUserDefinedMetricPythonCode, TraceThreadFilter> {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceThreadUserDefinedMetricPythonCode(
            @JsonView( {
                    View.Public.class, View.Write.class}) @NotNull String metric){

        public static final String CONTEXT_ARG_NAME = "context";
    }

    @ConstructorProperties({"id", "projectId", "projectName", "projects", "projectIds", "name", "samplingRate",
            "enabled", "filters", "code",
            "createdAt",
            "createdBy",
            "lastUpdatedAt", "lastUpdatedBy"})
    public AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython(UUID id, UUID projectId, String projectName,
            SortedSet<ProjectReference> projects,
            Set<UUID> projectIds,
            @NotBlank String name, float samplingRate, boolean enabled, List<TraceThreadFilter> filters,
            @NotNull TraceThreadUserDefinedMetricPythonCode code,
            Instant createdAt, String createdBy, Instant lastUpdatedAt, String lastUpdatedBy) {
        super(id, projectId, projectName, projects, projectIds, name, samplingRate, enabled, filters, code,
                createdAt, createdBy,
                lastUpdatedAt,
                lastUpdatedBy);
    }

    @JsonView({View.Public.class, View.Write.class})
    @Override
    public List<TraceThreadFilter> getFilters() {
        return super.getFilters();
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
