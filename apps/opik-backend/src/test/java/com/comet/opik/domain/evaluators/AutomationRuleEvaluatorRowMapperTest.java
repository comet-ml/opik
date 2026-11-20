package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.comet.opik.domain.evaluators.LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode;
import static com.comet.opik.domain.evaluators.TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode;
import static com.comet.opik.domain.evaluators.UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rows written before type changes were rejected can hold code of another evaluator type than their type column.
 * They can't be produced through the API anymore, so the row mapper is exercised directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationRuleEvaluatorRowMapperTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<AutomationRuleEvaluatorType, Class<? extends AutomationRuleEvaluatorModel<?>>> MODEL_CLASSES = Map
            .of(
                    AutomationRuleEvaluatorType.LLM_AS_JUDGE, LlmAsJudgeAutomationRuleEvaluatorModel.class,
                    AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON,
                    UserDefinedMetricPythonAutomationRuleEvaluatorModel.class,
                    AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE,
                    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.class,
                    AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
                    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.class,
                    AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE, SpanLlmAsJudgeAutomationRuleEvaluatorModel.class,
                    AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON,
                    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.class);

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();
    private final AutomationRuleEvaluatorRowMapper rowMapper = new AutomationRuleEvaluatorRowMapper();

    Stream<Arguments> map__whenCodeHoldsAnotherTypeFields__thenReadAsStoredType() throws Exception {
        var arguments = Stream.<Arguments>builder();
        for (var storedType : AutomationRuleEvaluatorType.values()) {
            var stored = manufacture(storedType);
            var otherKind = manufacture(otherKindType(storedType));

            var ownCodeNode = OBJECT_MAPPER.<ObjectNode>valueToTree(stored.code());
            var ownCodeWithOtherFields = ownCodeNode.deepCopy();
            Arrays.stream(AutomationRuleEvaluatorType.values())
                    .filter(type -> type != storedType)
                    .map(type -> OBJECT_MAPPER.<ObjectNode>valueToTree(manufacture(type).code()))
                    .forEach(otherCodeNode -> otherCodeNode.properties().stream()
                            .filter(field -> !ownCodeNode.has(field.getKey()))
                            .forEach(field -> ownCodeWithOtherFields.set(field.getKey(), field.getValue())));
            arguments.add(arguments(stored, OBJECT_MAPPER.writeValueAsString(ownCodeWithOtherFields), stored));

            var emptyCode = OBJECT_MAPPER.readValue("{}", stored.code().getClass());
            arguments.add(arguments(stored, OBJECT_MAPPER.writeValueAsString(otherKind.code()),
                    withCode(stored, emptyCode)));
        }
        return arguments.build();
    }

    @ParameterizedTest
    @MethodSource
    void map__whenCodeHoldsAnotherTypeFields__thenReadAsStoredType(
            AutomationRuleEvaluatorModel<?> stored, String codeJson, AutomationRuleEvaluatorModel<?> expected)
            throws Exception {
        var resultSet = resultSet(stored, codeJson);

        var actual = rowMapper.map(resultSet, null);

        assertThat(actual).isEqualTo(expected);
    }

    private AutomationRuleEvaluatorModel<?> manufacture(AutomationRuleEvaluatorType type) {
        var model = factory.manufacturePojo(MODEL_CLASSES.get(type))
                .withProjectIds(Set.of())
                .withProjectDetails(null, null, null);
        return withId(model);
    }

    private AutomationRuleEvaluatorModel<?> withId(AutomationRuleEvaluatorModel<?> model) {
        var id = idGenerator.generateId();
        return switch (model) {
            case LlmAsJudgeAutomationRuleEvaluatorModel judge -> judge.toBuilder().id(id).build();
            case UserDefinedMetricPythonAutomationRuleEvaluatorModel python -> python.toBuilder().id(id).build();
            case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel judge -> judge.toBuilder().id(id).build();
            case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel python ->
                python.toBuilder().id(id).build();
            case SpanLlmAsJudgeAutomationRuleEvaluatorModel judge -> judge.toBuilder().id(id).build();
            case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel python -> python.toBuilder().id(id).build();
        };
    }

    private static AutomationRuleEvaluatorModel<?> withCode(AutomationRuleEvaluatorModel<?> model, Object code) {
        return switch (model) {
            case LlmAsJudgeAutomationRuleEvaluatorModel judge ->
                judge.toBuilder().code((LlmAsJudgeCode) code).build();
            case UserDefinedMetricPythonAutomationRuleEvaluatorModel python ->
                python.toBuilder().code((UserDefinedMetricPythonCode) code).build();
            case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel judge ->
                judge.toBuilder().code((TraceThreadLlmAsJudgeCode) code).build();
            case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel python ->
                python.toBuilder().code((TraceThreadUserDefinedMetricPythonCode) code).build();
            case SpanLlmAsJudgeAutomationRuleEvaluatorModel judge ->
                judge.toBuilder().code((SpanLlmAsJudgeCode) code).build();
            case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel python ->
                python.toBuilder().code((SpanUserDefinedMetricPythonCode) code).build();
        };
    }

    /**
     * Judge code and Python code share no field, so the other kind's code leaves the stored type's code empty.
     */
    private static AutomationRuleEvaluatorType otherKindType(AutomationRuleEvaluatorType type) {
        return switch (type) {
            case LLM_AS_JUDGE -> AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;
            case USER_DEFINED_METRIC_PYTHON -> AutomationRuleEvaluatorType.LLM_AS_JUDGE;
            case TRACE_THREAD_LLM_AS_JUDGE -> AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
            case TRACE_THREAD_USER_DEFINED_METRIC_PYTHON -> AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;
            case SPAN_LLM_AS_JUDGE -> AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON;
            case SPAN_USER_DEFINED_METRIC_PYTHON -> AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
        };
    }

    private static ResultSet resultSet(AutomationRuleEvaluatorModel<?> model, String codeJson) throws Exception {
        var resultSet = mock(ResultSet.class);
        when(resultSet.getString("id")).thenReturn(model.id().toString());
        when(resultSet.getString("name")).thenReturn(model.name());
        when(resultSet.getFloat("sampling_rate")).thenReturn(model.samplingRate());
        when(resultSet.getBoolean("enabled")).thenReturn(model.enabled());
        when(resultSet.getString("trigger_scope")).thenReturn(model.triggerScope().getValue());
        when(resultSet.getString("filters")).thenReturn(model.filters());
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(model.createdAt()));
        when(resultSet.getString("created_by")).thenReturn(model.createdBy());
        when(resultSet.getTimestamp("last_updated_at")).thenReturn(Timestamp.from(model.lastUpdatedAt()));
        when(resultSet.getString("last_updated_by")).thenReturn(model.lastUpdatedBy());
        when(resultSet.getString("legacy_project_id")).thenReturn(null);
        when(resultSet.getString("type")).thenReturn(model.type().getType());
        when(resultSet.getString("code")).thenReturn(codeJson);
        return resultSet;
    }
}
