package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class FilterQueryBuilderSourceTest {

    static Stream<Arguments> sourceListFilters() {
        return Stream.of(
                arguments(Operator.IN, "playground,experiment", "(source IN :filter0)"),
                arguments(Operator.IN, "sdk, playground", "((source IN :filter0 OR source = 'unknown'))"),
                arguments(Operator.NOT_IN, "playground", "(source NOT IN :filter0)"),
                arguments(Operator.NOT_IN, "experiment,sdk", "((source NOT IN :filter0 AND source != 'unknown'))"));
    }

    @ParameterizedTest(name = "{0} \"{1}\"")
    @MethodSource("sourceListFilters")
    void spanSourceListAddsLegacyUnknownOnlyWithSdk(Operator operator, String value, String expectedPredicate) {
        var filter = SpanFilter.builder().field(SpanField.SOURCE).operator(operator).value(value).build();

        var actual = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.SPAN);

        assertThat(actual).contains("(%s)".formatted(expectedPredicate));
    }

    @ParameterizedTest(name = "{0} \"{1}\"")
    @MethodSource("sourceListFilters")
    void threadSourceListAddsLegacyUnknownOnlyWithSdk(Operator operator, String value, String expectedPredicate) {
        var filter = TraceThreadFilter.builder().field(TraceThreadField.SOURCE).operator(operator).value(value)
                .build();

        // The thread source filter runs on each thread's traces, not on the trace_threads row
        var actual = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE);
        var threadLevel = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE_THREAD);

        assertThat(actual).contains("(%s)".formatted(expectedPredicate));
        assertThat(threadLevel).isEmpty();
    }
}
