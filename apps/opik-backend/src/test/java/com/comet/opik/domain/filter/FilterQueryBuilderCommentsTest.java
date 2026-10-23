package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class FilterQueryBuilderCommentsTest {

    private static final String COMMENTS_SUBQUERY = "(SELECT entity_id FROM comments WHERE workspace_id = :workspace_id)";

    static Stream<Arguments> commentsFilter() {
        return Stream.of(
                arguments(FilterStrategy.TRACE, trace(Operator.IS_NOT_EMPTY), "id IN " + COMMENTS_SUBQUERY),
                arguments(FilterStrategy.TRACE, trace(Operator.IS_EMPTY), "id NOT IN " + COMMENTS_SUBQUERY),
                arguments(FilterStrategy.TRACE_THREAD, thread(Operator.IS_NOT_EMPTY),
                        "thread_model_id IN " + COMMENTS_SUBQUERY),
                arguments(FilterStrategy.TRACE_THREAD, thread(Operator.IS_EMPTY),
                        "thread_model_id NOT IN " + COMMENTS_SUBQUERY));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void commentsFilter(FilterStrategy strategy, Filter filter, String expected) {
        var actual = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), strategy);

        assertThat(actual).contains("((%s))".formatted(expected));
    }

    private static TraceFilter trace(Operator operator) {
        return TraceFilter.builder().field(TraceField.COMMENTS).operator(operator).value("").build();
    }

    private static TraceThreadFilter thread(Operator operator) {
        return TraceThreadFilter.builder().field(TraceThreadField.COMMENTS).operator(operator).value("").build();
    }
}
