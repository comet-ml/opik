package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.jdbi.v3.json.Json;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode;

/**
 * Trace Thread User Defined Metric Python automation rule evaluator model.
 * Uses @AllArgsConstructor(access = AccessLevel.PUBLIC) to generate a public constructor
 * that JDBI can use for reflection-based instantiation, solving the IllegalAccessException.
 */
@SuperBuilder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel
        extends
            AutomationRuleEvaluatorModelBase<TraceThreadUserDefinedMetricPythonCode>
        implements
            AutomationRuleEvaluatorModel<TraceThreadUserDefinedMetricPythonCode> {

    @Json

    @Builder.Default
    private final TraceThreadUserDefinedMetricPythonCode code = null;

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
    }

    @Override
    public AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds) {
        return toBuilder().projectIds(projectIds).build();
    }

    /**
     * Factory method for constructing from JDBI row mapper.
     * Encapsulates model-specific construction logic including JSON parsing.
     * Uses SuperBuilder's commonFields() convenience method for DRY.
     */
    public static TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel fromRowMapper(
            AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common,
            JsonNode codeNode,
            ObjectMapper objectMapper) throws JsonProcessingException {

        return builder()
                .commonFields(common) // ✨ SuperBuilder magic - sets all 12 common fields!
                .code(objectMapper.treeToValue(codeNode, TraceThreadUserDefinedMetricPythonCode.class))
                .build();
    }

    public record TraceThreadUserDefinedMetricPythonCode(String metric) {
    }
}
