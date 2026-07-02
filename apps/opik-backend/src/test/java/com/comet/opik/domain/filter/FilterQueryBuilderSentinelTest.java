package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Verifies the flag-gated sentinel handling in the filter SQL {@link FilterQueryBuilder} generates for the trace-column
 * non-nullable migration. Exercises the R2DBC entry point ({@code toAnalyticsDbFilters}); the v2-client placeholder
 * machinery is covered separately in {@link FilterQueryBuilderV2ClientTest}. The post-cutover behavior cannot be
 * exercised end-to-end while the columns are still {@code Nullable}, so the contract is asserted on the generated SQL.
 */
class FilterQueryBuilderSentinelTest {

    private static final String EPOCH = "toDateTime64('1970-01-01 00:00:00.000', 9)";
    private static final String END_TIME_SENTINEL_AWARE = "nullIf(end_time, %s)".formatted(EPOCH);
    private static final String DURATION_EXPR = ("if(end_time IS NOT NULL AND notEquals(end_time, %1$s) AND "
            + "start_time IS NOT NULL AND notEquals(start_time, %1$s), "
            + "(dateDiff('microsecond', start_time, end_time) / 1000.0), 0)").formatted(EPOCH);
    private static final String TTFT_EXPR = "if(isNaN(ttft), NULL, ttft)";

    static Stream<Arguments> endTimeFilterWrapsSentinelOnlyUnderFlag() {
        var value = Instant.now().toString();
        return Stream.of(
                arguments(FilterStrategy.TRACE, TraceFilter.builder()
                        .field(TraceField.END_TIME).operator(Operator.LESS_THAN).value(value).build()),
                arguments(FilterStrategy.TRACE_THREAD, TraceThreadFilter.builder()
                        .field(TraceThreadField.END_TIME).operator(Operator.LESS_THAN).value(value).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void endTimeFilterWrapsSentinelOnlyUnderFlag(FilterStrategy strategy, Filter filter) {
        var expectedWithFlag = singleFilter(END_TIME_SENTINEL_AWARE, "< parseDateTime64BestEffort(:filter0, 9)");
        var expectedWithoutFlag = singleFilter("end_time", "< parseDateTime64BestEffort(:filter0, 9)");

        var actualWithFlag = toSql(filter, strategy, true);
        var actualWithoutFlag = toSql(filter, strategy, false);

        assertThat(actualWithFlag).isEqualTo(expectedWithFlag);
        assertThat(actualWithoutFlag).isEqualTo(expectedWithoutFlag);
    }

    @Test
    void spanEndTimeFilterIgnoresFlagKeepingRawColumn() {
        var value = Instant.now().toString();
        var filter = SpanFilter.builder()
                .field(SpanField.END_TIME).operator(Operator.LESS_THAN).value(value).build();
        var expected = singleFilter("end_time", "< parseDateTime64BestEffort(:filter0, 9)");

        var actual = toSql(filter, FilterStrategy.SPAN, true);

        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> unconditionalSentinelFilterIgnoresFlag() {
        return Stream.of(
                arguments(TraceField.DURATION, Operator.GREATER_THAN, DURATION_EXPR, "> :filter0"),
                arguments(TraceField.TTFT, Operator.NOT_EQUAL, TTFT_EXPR, "!= :filter0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void unconditionalSentinelFilterIgnoresFlag(
            TraceField field, Operator operator, String columnExpr, String operatorAndPlaceholder) {
        var value = RandomStringUtils.secure().nextNumeric(32);
        var filter = TraceFilter.builder().field(field).operator(operator).value(value).build();
        var expected = singleFilter(columnExpr, operatorAndPlaceholder);

        var actualWithoutFlag = toSql(filter, FilterStrategy.TRACE, false);
        var actualWithFlag = toSql(filter, FilterStrategy.TRACE, true);

        assertThat(actualWithoutFlag).isEqualTo(expected);
        assertThat(actualWithFlag).isEqualTo(expected);
    }

    @Test
    void experimentComparisonDurationFilterExcludesNaNSentinel() {
        var filter = ExperimentsComparisonFilter.builder()
                .field(ExperimentsComparisonValidKnownField.DURATION.getQueryParamField())
                .operator(Operator.NOT_EQUAL)
                .value(RandomStringUtils.secure().nextNumeric(32))
                .build();
        var expected = singleFilter("if(isNaN(duration), NULL, duration)", "!= :filter0");

        var actualWithoutFlag = toSql(filter, FilterStrategy.EXPERIMENT_ITEM, false);
        var actualWithFlag = toSql(filter, FilterStrategy.EXPERIMENT_ITEM, true);

        assertThat(actualWithoutFlag).isEqualTo(expected);
        assertThat(actualWithFlag).isEqualTo(expected);
    }

    /**
     * Expected SQL for a single filter: toAnalyticsDbFilters wraps the AND-joined filters in (...), and each filter's
     * own predicate is itself parenthesized — hence the double parentheses.
     */
    private String singleFilter(String columnExpr, String operatorAndPlaceholder) {
        return "((%s %s))".formatted(columnExpr, operatorAndPlaceholder);
    }

    private String toSql(Filter filter, FilterStrategy strategy, boolean traceColumnsNonNullable) {
        return FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), strategy, traceColumnsNonNullable)
                .orElseThrow();
    }
}
