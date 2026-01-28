package com.comet.opik.api.validation;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserDefinedMetricCodeValidator Tests")
class UserDefinedMetricCodeValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @Nested
    @DisplayName("UserDefinedMetricPythonCode (Trace-level) Tests")
    class UserDefinedMetricPythonCodeTests {

        @Test
        @DisplayName("Valid: Common metric path with commonMetricId only")
        void validateWhenCommonMetricIdProvided() {
            // Given - SDK/common metric path
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .commonMetricId("common-metric-123")
                    .initConfig(Map.of("temperature", 0.7))
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Valid: Custom metric path with metric and arguments")
        void validateWhenMetricAndArgumentsProvided() {
            // Given - Custom Python code path
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .metric("def score(output, reference):\n    return 1.0")
                    .arguments(Map.of(
                            "output", "output.response",
                            "reference", "expected.value"))
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Valid: Common metric with initConfig and scoreConfig")
        void validateWhenCommonMetricWithConfigs() {
            // Given
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .commonMetricId("hallucination-metric")
                    .initConfig(Map.of("model", "gpt-4", "temperature", 0.5))
                    .scoreConfig(Map.of("threshold", 0.8))
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Valid: Both commonMetricId and metric present (commonMetricId takes precedence)")
        void validateWhenBothCommonMetricIdAndMetricProvided() {
            // Given - When both are present, commonMetricId takes precedence
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .commonMetricId("common-metric-123")
                    .metric("def score():\n    return 1.0")
                    .arguments(Map.of("output", "output"))
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then - This is valid (commonMetricId takes precedence)
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Invalid: Neither commonMetricId nor metric provided")
        void validateWhenNeitherCommonMetricIdNorMetricProvided() {
            // Given - Empty code object
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("common_metric_id")
                    .contains("metric")
                    .contains("must be provided");
        }

        @Test
        @DisplayName("Invalid: Metric provided without arguments")
        void validateWhenMetricProvidedWithoutArguments() {
            // Given - Custom metric path but missing arguments
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .metric("def score(output, reference):\n    return 1.0")
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("arguments")
                    .contains("must be provided");
        }

        @Test
        @DisplayName("Invalid: Metric provided with empty arguments map")
        void validateWhenMetricProvidedWithEmptyArguments() {
            // Given - Custom metric path but empty arguments
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .metric("def score(output, reference):\n    return 1.0")
                    .arguments(Map.of())
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("arguments")
                    .contains("must be provided")
                    .contains("non-empty");
        }

        @Test
        @DisplayName("Invalid: Blank commonMetricId and blank metric")
        void validateWhenBlankCommonMetricIdAndBlankMetric() {
            // Given - Both fields are blank strings
            var code = AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                    .commonMetricId("   ")
                    .metric("  ")
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("common_metric_id")
                    .contains("metric")
                    .contains("must be provided");
        }
    }

    @Nested
    @DisplayName("SpanUserDefinedMetricPythonCode (Span-level) Tests")
    class SpanUserDefinedMetricPythonCodeTests {

        @Test
        @DisplayName("Valid: Common metric path for spans")
        void validateWhenCommonMetricIdProvidedForSpans() {
            // Given
            var code = AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode.builder()
                    .commonMetricId("span-metric-456")
                    .initConfig(Map.of("threshold", 0.9))
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Valid: Custom metric path for spans")
        void validateWhenMetricAndArgumentsProvidedForSpans() {
            // Given
            var code = AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode.builder()
                    .metric("def score(input, output):\n    return len(output)")
                    .arguments(Map.of(
                            "input", "input.text",
                            "output", "output.text"))
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Invalid: Neither commonMetricId nor metric provided for spans")
        void validateWhenNeitherProvidedForSpans() {
            // Given
            var code = AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode.builder()
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("common_metric_id")
                    .contains("metric");
        }

        @Test
        @DisplayName("Invalid: Metric without arguments for spans")
        void validateWhenMetricWithoutArgumentsForSpans() {
            // Given
            var code = AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode.builder()
                    .metric("def score(input):\n    return 1.0")
                    .build();

            // When
            Set<ConstraintViolation<AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode>> violations = validator
                    .validate(code);

            // Then
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("arguments");
        }
    }
}
