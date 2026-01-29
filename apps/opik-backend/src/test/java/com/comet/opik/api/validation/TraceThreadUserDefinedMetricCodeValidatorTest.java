package com.comet.opik.api.validation;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceThreadUserDefinedMetricCodeValidator Tests")
class TraceThreadUserDefinedMetricCodeValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @Test
    @DisplayName("Valid: Common metric path with commonMetricId")
    void validateWhenCommonMetricIdProvided() {
        // Given - SDK/common metric path for thread
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .commonMetricId("thread-metric-789")
                .initConfig(Map.of("model", "gpt-4"))
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Custom metric path with metric")
    void validateWhenMetricProvided() {
        // Given - Custom Python code path for thread (no arguments field for threads)
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .metric("def score(context):\n    return len(context)")
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Common metric with initConfig")
    void validateWhenCommonMetricWithInitConfig() {
        // Given
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .commonMetricId("moderation-metric")
                .initConfig(Map.of(
                        "model", "gpt-4",
                        "temperature", 0.3,
                        "max_tokens", 1000))
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Both commonMetricId and metric present (commonMetricId takes precedence)")
    void validateWhenBothCommonMetricIdAndMetricProvided() {
        // Given - When both are present, commonMetricId takes precedence
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .commonMetricId("thread-metric-123")
                .metric("def score(context):\n    return 1.0")
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then - This is valid (commonMetricId takes precedence)
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Invalid: Neither commonMetricId nor metric provided")
    void validateWhenNeitherCommonMetricIdNorMetricProvided() {
        // Given - Empty code object
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("common_metric_id")
                .contains("metric")
                .contains("must be provided");
    }

    @Test
    @DisplayName("Invalid: Blank commonMetricId and blank metric")
    void validateWhenBlankCommonMetricIdAndBlankMetric() {
        // Given - Both fields are blank strings
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .commonMetricId("   ")
                .metric("  ")
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("common_metric_id")
                .contains("metric")
                .contains("must be provided");
    }

    @Test
    @DisplayName("Valid: Metric with empty initConfig")
    void validateWhenMetricWithEmptyInitConfig() {
        // Given - Custom metric with empty initConfig is valid
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .metric("def score(context):\n    return 1.0")
                .initConfig(Map.of())
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Metric with null initConfig")
    void validateWhenMetricWithNullInitConfig() {
        // Given - Custom metric with null initConfig is valid
        var code = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode
                .builder()
                .metric("def score(context):\n    return 1.0")
                .initConfig(null)
                .build();

        // When
        Set<ConstraintViolation<AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode>> violations = validator
                .validate(code);

        // Then
        assertThat(violations).isEmpty();
    }
}
