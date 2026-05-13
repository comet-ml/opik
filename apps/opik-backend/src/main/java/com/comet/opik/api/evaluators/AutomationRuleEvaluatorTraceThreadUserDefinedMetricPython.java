package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.TraceThreadFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.Map;
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

    /**
     * Reserved metric-argument keys whose presence on a thread rule triggers opt-in pre-fetch
     * of the corresponding entities by {@code OnlineScoringTraceThreadUserDefinedMetricPythonScorer}:
     * <ul>
     *   <li>{@code spans} — flat list of every span across all traces in the thread.</li>
     *   <li>{@code traces} — list of full trace objects for the thread.</li>
     * </ul>
     * Backward-compatible: rules that don't declare these keep the legacy single-positional
     * messages-list signature (the sandbox runner branches on data shape).
     */
    public static final String SPANS_ARG_NAME = "spans";
    public static final String TRACES_ARG_NAME = "traces";

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceThreadUserDefinedMetricPythonCode(
            @JsonView( {
                    View.Public.class, View.Write.class}) @NotNull String metric,
            @JsonView({View.Public.class,
                    View.Write.class}) @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> arguments){

        public static final String CONTEXT_ARG_NAME = "context";

        /**
         * Convenience constructor for the legacy single-positional-arg shape (no opt-in
         * spans / traces). Keeps existing call sites and serialized payloads that don't
         * carry an {@code arguments} field compiling without changes.
         */
        public TraceThreadUserDefinedMetricPythonCode(String metric) {
            this(metric, null);
        }
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
