package com.comet.opik.api.metrics;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakdownQueryBuilderTest {

    @ParameterizedTest
    @ValueSource(strings = {"TAGS", "METADATA", "NAME", "ERROR_INFO", "ERROR_TYPE", "MODEL", "PROVIDER", "TYPE"})
    @DisplayName("validate: allows span cost breakdown fields")
    void validate_allowsSpanCostBreakdownFields(String fieldName) {
        var breakdown = spanCostBreakdown(fieldName);

        assertThatCode(() -> BreakdownQueryBuilder.validate(breakdown, MetricType.SPAN_COST))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TAGS", "METADATA", "NAME", "ERROR_INFO", "ERROR_TYPE", "MODEL", "PROVIDER", "TYPE"})
    @DisplayName("getBreakdownGroupExpression: uses span alias for span cost")
    void getBreakdownGroupExpression_usesSpanAliasForSpanCost(String fieldName) {
        var breakdown = spanCostBreakdown(fieldName);

        assertThat(BreakdownQueryBuilder.getBreakdownGroupExpression(MetricType.SPAN_COST, breakdown))
                .contains("s.")
                .doesNotContain("t.");
    }

    private static BreakdownConfig spanCostBreakdown(String fieldName) {
        var field = BreakdownField.valueOf(fieldName);
        var builder = BreakdownConfig.builder().field(field);
        if (field == BreakdownField.METADATA) {
            builder.metadataKey("env");
        }
        return builder.build();
    }

    @ParameterizedTest
    @CsvSource({"p50,0.5", "p90,0.9", "p99,0.99", "P50,0.5", "P99,0.99"})
    @DisplayName("mapQuantile: maps allowed percentiles to numeric quantile literals")
    void mapQuantile_allowed(String subMetric, String expected) {
        assertThat(BreakdownQueryBuilder.mapQuantile(subMetric)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "p95",
            "0.5",
            "x' OR sleep(3) OR '",
            "0.5)(duration) AS v, 1 --",
            "' UNION SELECT 1 --"})
    @DisplayName("mapQuantile: rejects any value outside the allow-list instead of leaking it into SQL")
    void mapQuantile_rejectsInjection(String subMetric) {
        assertThatThrownBy(() -> BreakdownQueryBuilder.mapQuantile(subMetric))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid sub_metric");
    }
}
