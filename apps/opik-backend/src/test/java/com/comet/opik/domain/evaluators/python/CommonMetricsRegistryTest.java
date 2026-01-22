package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Common Metrics Registry Test")
class CommonMetricsRegistryTest {

    private CommonMetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommonMetricsRegistry();
    }

    @Test
    @DisplayName("should load metrics from bundled Python files")
    void getAll__shouldReturnLoadedMetrics() {
        // When
        CommonMetric.CommonMetricList result = registry.getAll();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.content()).isNotEmpty();

        // Verify some expected metrics are present
        assertThat(result.content())
                .extracting(CommonMetric::name)
                .contains("Equals", "Contains", "IsJson", "LevenshteinRatio");
    }

    @Test
    @DisplayName("should return metrics sorted alphabetically")
    void getAll__shouldReturnSortedMetrics() {
        // When
        CommonMetric.CommonMetricList result = registry.getAll();

        // Then
        assertThat(result.content())
                .extracting(CommonMetric::name)
                .isSorted();
    }

    @Test
    @DisplayName("should find metric by ID")
    void findById__whenMetricExists__shouldReturnMetric() {
        // When
        CommonMetric metric = registry.findById("equals");

        // Then
        assertThat(metric).isNotNull();
        assertThat(metric.name()).isEqualTo("Equals");
        assertThat(metric.description()).isNotBlank();
        assertThat(metric.code()).contains("class Equals");
        assertThat(metric.parameters()).isNotEmpty();
    }

    @Test
    @DisplayName("should return null for non-existent metric ID")
    void findById__whenMetricNotExists__shouldReturnNull() {
        // When
        CommonMetric metric = registry.findById("non_existent_metric");

        // Then
        assertThat(metric).isNull();
    }

    @Test
    @DisplayName("should load metrics with valid parameters")
    void getAll__shouldLoadMetricsWithValidParameters() {
        // When
        CommonMetric.CommonMetricList result = registry.getAll();

        // Then - all loaded metrics should have at least one required parameter
        for (CommonMetric metric : result.content()) {
            assertThat(metric.parameters())
                    .as("Metric '%s' should have parameters", metric.name())
                    .isNotEmpty();

            boolean hasRequiredParam = metric.parameters().stream()
                    .anyMatch(CommonMetric.ScoreParameter::required);
            assertThat(hasRequiredParam)
                    .as("Metric '%s' should have at least one required parameter", metric.name())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("should load metrics with code containing the class definition")
    void getAll__shouldLoadMetricsWithCode() {
        // When
        CommonMetric.CommonMetricList result = registry.getAll();

        // Then
        for (CommonMetric metric : result.content()) {
            assertThat(metric.code())
                    .as("Metric '%s' should have code", metric.name())
                    .isNotBlank()
                    .contains("class " + metric.name());
        }
    }

    @Test
    @DisplayName("should return immutable list")
    void getMetrics__shouldReturnImmutableList() {
        // When
        var metrics = registry.getMetrics();

        // Then
        assertThat(metrics).isUnmodifiable();
    }
}
