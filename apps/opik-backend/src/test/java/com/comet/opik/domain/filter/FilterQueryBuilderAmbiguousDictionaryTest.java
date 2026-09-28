package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class FilterQueryBuilderAmbiguousDictionaryTest {

    @Test
    void metadataContainsOnDottedFlatKeyUsesNestedAndLiteralJsonPaths() {
        var filter = TraceFilter.builder()
                .field(TraceField.METADATA)
                .operator(Operator.CONTAINS)
                .key("uni.workflow.id")
                .value("019e7212")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE, false)
                .orElseThrow();

        assertThat(sql)
                .contains("JSON_VALUE(metadata, :filterKey0)")
                .contains("JSON_VALUE(metadata, :filterKeyLiteral0)")
                .contains(" OR ");
    }

    @Test
    void metadataNotContainsOnDottedFlatKeyCombinesWithAnd() {
        var filter = TraceFilter.builder()
                .field(TraceField.METADATA)
                .operator(Operator.NOT_CONTAINS)
                .key("uni.workflow.id")
                .value("missing")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE, false)
                .orElseThrow();

        assertThat(sql).contains(" AND ");
    }

    @Test
    void v2ClientRewritesLiteralKeyPlaceholderAndBindsPaths() {
        var filter = TraceFilter.builder()
                .field(TraceField.METADATA)
                .operator(Operator.EQUAL)
                .key("a.b.c")
                .value("x")
                .build();
        var sql = FilterQueryBuilder.toAnalyticsDbFiltersV2Client(List.of(filter), FilterStrategy.TRACE, false)
                .orElseThrow();

        assertThat(sql).contains("{filterKeyLiteral0:String}");

        Map<String, Object> params = new HashMap<>();
        FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.TRACE);

        assertThat(params)
                .containsEntry("filterKey0", "$.a.b.c")
                .containsEntry("filterKeyLiteral0", "$['a.b.c']")
                .containsEntry("filter0", "x");
    }

    @ParameterizedTest
    @EnumSource(value = Operator.class, names = {"EQUAL", "CONTAINS", "IS_NOT_EMPTY"})
    void positiveMatchOperatorsCombineAmbiguousPathsWithOr(Operator operator) {
        var filter = TraceFilter.builder()
                .field(TraceField.METADATA)
                .operator(operator)
                .key("uni.workflow.id")
                .value(operator == Operator.IS_NOT_EMPTY ? "" : "v")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE, false)
                .orElseThrow();

        assertThat(sql).contains(" OR ");
    }

    @ParameterizedTest
    @MethodSource("negationAndEmptinessOperators")
    void negationOperatorsCombineAmbiguousPathsWithAnd(Operator operator) {
        var filter = TraceFilter.builder()
                .field(TraceField.METADATA)
                .operator(operator)
                .key("uni.workflow.id")
                .value(operator == Operator.IS_EMPTY ? "" : "missing")
                .build();

        var sql = FilterQueryBuilder.toAnalyticsDbFilters(List.of(filter), FilterStrategy.TRACE, false)
                .orElseThrow();

        assertThat(sql).contains(" AND ");
    }

    private static Stream<Arguments> negationAndEmptinessOperators() {
        return Stream.of(
                arguments(Operator.NOT_CONTAINS),
                arguments(Operator.NOT_EQUAL),
                arguments(Operator.IS_EMPTY));
    }
}
