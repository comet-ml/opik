package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.comet.opik.domain.evaluators.LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rows written before type changes were rejected can hold code of the other evaluator kind than their type column.
 * They can't be produced through the API anymore, so the row mapper is exercised directly.
 */
class AutomationRuleEvaluatorRowMapperTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final AutomationRuleEvaluatorRowMapper rowMapper = new AutomationRuleEvaluatorRowMapper();

    @Test
    void map__whenPythonRowHoldsJudgeCode__thenReadAsJudge() throws Exception {
        var code = new LlmAsJudgeCode(
                new LlmAsJudgeCodeParameters(RandomStringUtils.secure().nextAlphanumeric(10), 0.5, 7,
                        OBJECT_MAPPER.createObjectNode().put("max_tokens", 100), null),
                List.of(new LlmAsJudgeCodeMessage("USER", RandomStringUtils.secure().nextAlphanumeric(20))),
                Map.of("input", "input"),
                List.of(new LlmAsJudgeCodeSchema(RandomStringUtils.secure().nextAlphanumeric(10),
                        LlmAsJudgeOutputSchemaType.BOOLEAN, RandomStringUtils.secure().nextAlphanumeric(20))),
                new BigDecimal("1.25"));
        var expected = LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                .id(ID_GENERATOR.generate())
                .projectIds(Set.of())
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .samplingRate(0.5f)
                .enabled(true)
                .triggerScope(EvalTriggerScope.PRODUCTION)
                .code(code)
                .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .createdBy(RandomStringUtils.secure().nextAlphanumeric(10))
                .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .lastUpdatedBy(RandomStringUtils.secure().nextAlphanumeric(10))
                .build();
        var resultSet = resultSet(expected, AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON,
                OBJECT_MAPPER.writeValueAsString(code));

        var actual = rowMapper.map(resultSet, null);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void map__whenPythonRowHoldsPythonCode__thenReadAsPython() throws Exception {
        var code = new UserDefinedMetricPythonCode(RandomStringUtils.secure().nextAlphanumeric(20),
                Map.of("output", "output"));
        var expected = UserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                .id(ID_GENERATOR.generate())
                .projectIds(Set.of())
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .samplingRate(0.5f)
                .enabled(true)
                .triggerScope(EvalTriggerScope.PRODUCTION)
                .code(code)
                .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .createdBy(RandomStringUtils.secure().nextAlphanumeric(10))
                .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .lastUpdatedBy(RandomStringUtils.secure().nextAlphanumeric(10))
                .build();
        var resultSet = resultSet(expected, AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON,
                OBJECT_MAPPER.writeValueAsString(code));

        var actual = rowMapper.map(resultSet, null);

        assertThat(actual).isEqualTo(expected);
    }

    private static ResultSet resultSet(AutomationRuleEvaluatorModel<?> model, AutomationRuleEvaluatorType storedType,
            String codeJson) throws Exception {
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
        when(resultSet.getString("type")).thenReturn(storedType.getType());
        when(resultSet.getString("code")).thenReturn(codeJson);
        return resultSet;
    }
}
