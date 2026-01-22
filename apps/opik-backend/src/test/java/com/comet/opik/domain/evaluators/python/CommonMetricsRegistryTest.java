package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Common Metrics Registry Test")
class CommonMetricsRegistryTest {

    private CommonMetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommonMetricsRegistry();
    }

    @Nested
    @DisplayName("getAll")
    class GetAll {

        @Test
        @DisplayName("should return all predefined metrics")
        void shouldReturnAllPredefinedMetrics() {
            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            assertThat(result).isNotNull();
            assertThat(result.content()).isNotEmpty();
            assertThat(result.content()).hasSizeGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("should return metrics with correct structure")
        void shouldReturnMetricsWithCorrectStructure() {
            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            for (CommonMetric metric : result.content()) {
                assertThat(metric.id()).isNotBlank();
                assertThat(metric.name()).isNotBlank();
                assertThat(metric.description()).isNotBlank();
                assertThat(metric.scoreParameters()).isNotNull();
                assertThat(metric.initParameters()).isNotNull();
            }
        }

        @Test
        @DisplayName("should include equals metric")
        void shouldIncludeEqualsMetric() {
            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric equalsMetric = result.content().stream()
                    .filter(m -> m.id().equals("equals"))
                    .findFirst()
                    .orElse(null);

            assertThat(equalsMetric).isNotNull();
            assertThat(equalsMetric.name()).isEqualTo("Equals");
            assertThat(equalsMetric.scoreParameters()).hasSize(2);
            assertThat(equalsMetric.initParameters()).hasSize(1);
        }

        @Test
        @DisplayName("should include contains metric with init parameters")
        void shouldIncludeContainsMetricWithInitParameters() {
            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric containsMetric = result.content().stream()
                    .filter(m -> m.id().equals("contains"))
                    .findFirst()
                    .orElse(null);

            assertThat(containsMetric).isNotNull();
            assertThat(containsMetric.name()).isEqualTo("Contains");
            assertThat(containsMetric.initParameters()).hasSize(2);

            // Check case_sensitive init parameter
            CommonMetric.InitParameter caseSensitiveParam = containsMetric.initParameters().stream()
                    .filter(p -> p.name().equals("case_sensitive"))
                    .findFirst()
                    .orElse(null);

            assertThat(caseSensitiveParam).isNotNull();
            assertThat(caseSensitiveParam.type()).isEqualTo("bool");
            assertThat(caseSensitiveParam.defaultValue()).isEqualTo("False");
            assertThat(caseSensitiveParam.required()).isFalse();
        }

        @Test
        @DisplayName("should include regex_match metric with required init parameter")
        void shouldIncludeRegexMatchMetricWithRequiredInitParameter() {
            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric regexMetric = result.content().stream()
                    .filter(m -> m.id().equals("regex_match"))
                    .findFirst()
                    .orElse(null);

            assertThat(regexMetric).isNotNull();
            assertThat(regexMetric.name()).isEqualTo("RegexMatch");

            // Check regex init parameter is required
            CommonMetric.InitParameter regexParam = regexMetric.initParameters().stream()
                    .filter(p -> p.name().equals("regex"))
                    .findFirst()
                    .orElse(null);

            assertThat(regexParam).isNotNull();
            assertThat(regexParam.required()).isTrue();
        }

        @Test
        @DisplayName("should include is_json metric without init parameters")
        void shouldIncludeIsJsonMetricWithoutInitParameters() {
            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric isJsonMetric = result.content().stream()
                    .filter(m -> m.id().equals("is_json"))
                    .findFirst()
                    .orElse(null);

            assertThat(isJsonMetric).isNotNull();
            assertThat(isJsonMetric.name()).isEqualTo("IsJson");
            assertThat(isJsonMetric.initParameters()).isEmpty();
            assertThat(isJsonMetric.scoreParameters()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("should return metric when found")
        void shouldReturnMetricWhenFound() {
            // When
            CommonMetric metric = registry.findById("equals");

            // Then
            assertThat(metric).isNotNull();
            assertThat(metric.id()).isEqualTo("equals");
            assertThat(metric.name()).isEqualTo("Equals");
        }

        @Test
        @DisplayName("should return null when metric not found")
        void shouldReturnNullWhenMetricNotFound() {
            // When
            CommonMetric metric = registry.findById("non_existent_metric");

            // Then
            assertThat(metric).isNull();
        }

        @Test
        @DisplayName("should find levenshtein_ratio metric")
        void shouldFindLevenshteinRatioMetric() {
            // When
            CommonMetric metric = registry.findById("levenshtein_ratio");

            // Then
            assertThat(metric).isNotNull();
            assertThat(metric.name()).isEqualTo("LevenshteinRatio");
        }
    }
}
