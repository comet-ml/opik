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

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode;

/**
 * Span User Defined Metric Python automation rule evaluator model.
 */
@SuperBuilder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel
        extends
            AutomationRuleEvaluatorModelBase<SpanUserDefinedMetricPythonCode>
        implements
            AutomationRuleEvaluatorModel<SpanUserDefinedMetricPythonCode> {

    @Builder.Default
    private final SpanUserDefinedMetricPythonCode code = null;

    /**
     * Explicit override to apply @Json annotation for JDBI serialization.
     * Lombok's @Getter doesn't preserve annotations from fields on generated methods.
     */
    @Override
    @Json
    public SpanUserDefinedMetricPythonCode code() {
        return code;
    }

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
     * Uses SuperBuilder's commonFields() convenience method for DRY.
     */
    public static SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel fromRowMapper(
            AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common,
            JsonNode codeNode,
            ObjectMapper objectMapper) throws JsonProcessingException {

        return builder()
                .commonFields(common) // ✨ SuperBuilder magic - sets all 12 common fields!
                .code(objectMapper.treeToValue(codeNode, SpanUserDefinedMetricPythonCode.class))
                .build();
    }

    public record SpanUserDefinedMetricPythonCode(String metric, Map<String, String> arguments) {
    }
}
