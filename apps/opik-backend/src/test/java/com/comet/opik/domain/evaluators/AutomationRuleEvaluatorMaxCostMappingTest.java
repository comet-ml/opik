package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-evaluation {@code maxCostUsd} must survive the API↔domain hop, since the domain code record
 * is what gets serialized into the {@code code} JSON column. A null budget (rules created before this
 * field existed, or with no limit set) must round-trip as null.
 */
class AutomationRuleEvaluatorMaxCostMappingTest {

    private static final AutomationModelEvaluatorMapper MAPPER = AutomationModelEvaluatorMapper.INSTANCE;

    private static LlmAsJudgeModelParameters model() {
        return LlmAsJudgeModelParameters.builder().name("gpt-4o").build();
    }

    @Test
    void traceCodeRoundTripsMaxCostUsd() {
        var apiCode = new AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode(
                model(), List.of(), Map.of(), List.of(), new BigDecimal("12.50"));

        var domainCode = MAPPER.map(apiCode);
        assertThat(domainCode.maxCostUsd()).isEqualByComparingTo("12.50");

        var backToApi = MAPPER.map(domainCode);
        assertThat(backToApi.maxCostUsd()).isEqualByComparingTo("12.50");
    }

    @Test
    void threadCodeRoundTripsMaxCostUsd() {
        var apiCode = new AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode(
                model(), List.of(), List.of(), new BigDecimal("3.75"));

        var domainCode = MAPPER.map(apiCode);
        assertThat(domainCode.maxCostUsd()).isEqualByComparingTo("3.75");

        var backToApi = MAPPER.map(domainCode);
        assertThat(backToApi.maxCostUsd()).isEqualByComparingTo("3.75");
    }

    @Test
    void nullBudgetRoundTripsAsNull() {
        var apiCode = new AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode(
                model(), List.of(), Map.of(), List.of());

        assertThat(apiCode.maxCostUsd()).isNull();
        assertThat(MAPPER.map(apiCode).maxCostUsd()).isNull();
        assertThat(MAPPER.map(MAPPER.map(apiCode)).maxCostUsd()).isNull();
    }
}
