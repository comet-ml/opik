package com.comet.opik.domain.evaluators;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Evaluators store their filters as a flat list in the {@code automation_rules.filters} column, which annotation
 * queue automations share as OR-ed groups. Evaluators must read the same filters from either shape, so the
 * column can move to groups without online scoring noticing.
 */
class AutomationRuleEvaluatorFiltersMappingTest {

    private static final AutomationModelEvaluatorMapper MAPPER = AutomationModelEvaluatorMapper.INSTANCE;

    private static final List<Filter> TRACE_FILTERS = List.of(
            TraceFilter.builder().field(TraceField.TAGS).operator(Operator.CONTAINS).value("prod").build(),
            TraceFilter.builder().field(TraceField.FEEDBACK_SCORES).operator(Operator.LESS_THAN).key("safety")
                    .value("0.5").build());
    private static final List<Filter> THREAD_FILTERS = List.of(
            TraceThreadFilter.builder().field(TraceThreadField.NUMBER_OF_MESSAGES)
                    .operator(Operator.GREATER_THAN_EQUAL).value("3").build());
    private static final List<Filter> SPAN_FILTERS = List.of(
            SpanFilter.builder().field(SpanField.NAME).operator(Operator.EQUAL).value("llm-call").build());

    static Stream<Arguments> evaluators() {
        return Stream.of(
                arguments("llm as judge", (ModelWithFilters) json -> LlmAsJudgeAutomationRuleEvaluatorModel
                        .builder().filters(json).build(), TRACE_FILTERS),
                arguments("python metric",
                        (ModelWithFilters) json -> UserDefinedMetricPythonAutomationRuleEvaluatorModel
                                .builder().filters(json).build(),
                        TRACE_FILTERS),
                arguments("thread llm as judge",
                        (ModelWithFilters) json -> TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .filters(json).build(),
                        THREAD_FILTERS),
                arguments("thread python metric",
                        (ModelWithFilters) json -> TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel
                                .builder().filters(json).build(),
                        THREAD_FILTERS),
                arguments("span llm as judge", (ModelWithFilters) json -> SpanLlmAsJudgeAutomationRuleEvaluatorModel
                        .builder().filters(json).build(), SPAN_FILTERS),
                arguments("span python metric",
                        (ModelWithFilters) json -> SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .filters(json).build(),
                        SPAN_FILTERS));
    }

    @FunctionalInterface
    interface ModelWithFilters {
        AutomationRuleEvaluatorModel<?> withFilters(String filtersJson);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evaluators")
    void readsAFlatList(String label, ModelWithFilters model, List<Filter> filters) {
        String flat = MAPPER.map(filters);

        assertThat(MAPPER.<Filter>mapFilters(model.withFilters(flat))).isEqualTo(filters);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evaluators")
    void readsASingleGroup(String label, ModelWithFilters model, List<Filter> filters) {
        String grouped = JsonUtils.writeValueAsString(List.of(filters));

        assertThat(MAPPER.<Filter>mapFilters(model.withFilters(grouped))).isEqualTo(filters);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evaluators")
    void readsNoFiltersAsEmpty(String label, ModelWithFilters model, List<Filter> filters) {
        assertThat(MAPPER.<Filter>mapFilters(model.withFilters(null))).isEmpty();
        assertThat(MAPPER.<Filter>mapFilters(model.withFilters("[]"))).isEmpty();
    }
}
