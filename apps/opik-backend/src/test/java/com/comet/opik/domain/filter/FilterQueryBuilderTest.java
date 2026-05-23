package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterQueryBuilderTest {

    private final FilterQueryBuilder filterQueryBuilder = new FilterQueryBuilder();
    private final FiltersFactory filtersFactory = new FiltersFactory(filterQueryBuilder);

    @Test
    void getSupportedOperators__whenErrorInfo__thenIncludesTextOperators() {
        assertThat(filterQueryBuilder.getSupportedOperators(TraceField.ERROR_INFO).get(TraceField.ERROR_INFO))
                .contains(Operator.CONTAINS, Operator.NOT_CONTAINS, Operator.IS_EMPTY, Operator.IS_NOT_EMPTY);

        assertThat(filterQueryBuilder.getSupportedOperators(SpanField.ERROR_INFO).get(SpanField.ERROR_INFO))
                .contains(Operator.CONTAINS, Operator.NOT_CONTAINS, Operator.IS_EMPTY, Operator.IS_NOT_EMPTY);
    }

    @Test
    void toAnalyticsDbFilters__whenTraceErrorInfoContains__thenSearchesErrorInfoJson() {
        var filter = TraceFilter.builder()
                .field(TraceField.ERROR_INFO)
                .operator(Operator.CONTAINS)
                .value("CancelledError")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("ilike(error_info, CONCAT('%', :filter0 ,'%'))"));
    }

    @Test
    void toAnalyticsDbFilters__whenSpanErrorInfoDoesNotContain__thenSearchesNonEmptyErrorInfoJson() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .operator(Operator.NOT_CONTAINS)
                .value("CancelledError")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.SPAN);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("notEmpty(error_info)")
                .contains("notILike(error_info, CONCAT('%', :filter0 ,'%'))"));
    }

    @Test
    void toAnalyticsDbFilters__whenSpanErrorInfoTracebackDoesNotContain__thenSearchesNonEmptyTraceback() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .key("traceback")
                .operator(Operator.NOT_CONTAINS)
                .value("CancelledError")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.SPAN);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("notEmpty(simpleJSONExtractString(error_info, 'traceback'))")
                .contains("notILike(simpleJSONExtractString(error_info, 'traceback'), CONCAT('%', :filter0 ,'%'))"));
    }

    @Test
    void toAnalyticsDbFilters__whenSpanErrorInfoTracebackIsEmpty__thenChecksTracebackOnly() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .key("traceback")
                .operator(Operator.IS_EMPTY)
                .value("")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.SPAN);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("empty(simpleJSONExtractString(error_info, 'traceback'))"));
    }

    @Test
    void toAnalyticsDbFilters__whenSpanErrorInfoTracebackIsNotEmpty__thenChecksTracebackOnly() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .key("traceback")
                .operator(Operator.IS_NOT_EMPTY)
                .value("")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.SPAN);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("notEmpty(simpleJSONExtractString(error_info, 'traceback'))"));
    }

    @Test
    void toAnalyticsDbFilters__whenTraceErrorInfoMessageContains__thenSearchesMessage() {
        var filter = TraceFilter.builder()
                .field(TraceField.ERROR_INFO)
                .key("message")
                .operator(Operator.CONTAINS)
                .value("timeout")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("ilike(simpleJSONExtractString(error_info, 'message'), CONCAT('%', :filter0 ,'%'))"));
    }

    @Test
    void toAnalyticsDbFilters__whenTraceErrorInfoExceptionTypeContains__thenSearchesExceptionType() {
        var filter = TraceFilter.builder()
                .field(TraceField.ERROR_INFO)
                .key(" EXCEPTION_TYPE ")
                .operator(Operator.CONTAINS)
                .value("ValueError")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE);

        assertThat(sql).hasValueSatisfying(value -> assertThat(value)
                .contains("ilike(simpleJSONExtractString(error_info, 'exception_type'), CONCAT('%', :filter0 ,'%'))"));
    }

    @Test
    void validateFilter__whenErrorInfoContainsHasValue__thenAcceptsFilter() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .operator(Operator.CONTAINS)
                .value("CancelledError")
                .build();

        assertThatCode(() -> filtersFactory.validateFilter(List.of(filter))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("errorInfoLiteralTextFilters")
    void validateFilter__whenSpanErrorInfoTextFilterHasLiteralValue__thenPreservesValue(Operator operator,
            String value) {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .operator(operator)
                .value(value)
                .build();

        var validatedFilters = filtersFactory.validateFilter(List.of(filter));

        assertThat(validatedFilters).singleElement()
                .satisfies(validatedFilter -> assertThat(validatedFilter.value()).isEqualTo(value));
    }

    @Test
    void newFilters__whenSpanErrorInfoTextFiltersHaveLiteralValues__thenPreservesValues() {
        var queryParam = """
                [
                    {"field":"error_info","operator":"contains","value":"C++"},
                    {"field":"error_info","operator":"not_contains","value":"100%"}
                ]
                """;

        var filters = filtersFactory.newFilters(queryParam, SpanFilter.LIST_TYPE_REFERENCE);

        assertThat(filters)
                .extracting(Filter::value)
                .containsExactly("C++", "100%");
    }

    @Test
    void validateFilter__whenErrorInfoContainsHasBlankValue__thenRejectsFilter() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .operator(Operator.CONTAINS)
                .value(" ")
                .build();

        assertThatThrownBy(() -> filtersFactory.validateFilter(List.of(filter)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid value");
    }

    @Test
    void validateFilter__whenErrorInfoKeyIsUnsupported__thenRejectsFilter() {
        var filter = SpanFilter.builder()
                .field(SpanField.ERROR_INFO)
                .key("unknown")
                .operator(Operator.CONTAINS)
                .value("CancelledError")
                .build();

        assertThatThrownBy(() -> filtersFactory.validateFilter(List.of(filter)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid value");
    }

    private static Stream<Arguments> errorInfoLiteralTextFilters() {
        return Stream.of(
                Arguments.of(Operator.CONTAINS, "C++"),
                Arguments.of(Operator.NOT_CONTAINS, "C++"),
                Arguments.of(Operator.CONTAINS, "100%"),
                Arguments.of(Operator.NOT_CONTAINS, "100%"));
    }
}
