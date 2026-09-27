package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void v2ClientRewritesLiteralKeyPlaceholder() {
        var filter = TraceFilter.builder()
                .field(TraceField.METADATA)
                .operator(Operator.EQUAL)
                .key("a.b.c")
                .value("x")
                .build();
        var sql = FilterQueryBuilder.toAnalyticsDbFiltersV2Client(List.of(filter), FilterStrategy.TRACE, false)
                .orElseThrow();

        assertThat(sql).contains("{filterKeyLiteral0:String}");
    }
}
