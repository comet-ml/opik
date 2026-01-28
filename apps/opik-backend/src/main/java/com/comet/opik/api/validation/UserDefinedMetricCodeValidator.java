package com.comet.opik.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * Validator for UserDefinedMetricPythonCode records that enforces the union constraint:
 * - Either commonMetricId must be present (SDK/common metric path)
 * - OR both metric and arguments must be present (custom Python code path)
 *
 * This validator works with any record that has:
 * - String commonMetricId
 * - String metric
 * - Map<String, ?> arguments
 */
public class UserDefinedMetricCodeValidator
        implements
            ConstraintValidator<UserDefinedMetricCodeValidation, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull should handle null checks
        }

        context.disableDefaultConstraintViolation();

        try {
            // Use reflection to get the fields from the record
            var clazz = value.getClass();
            var commonMetricIdMethod = clazz.getMethod("commonMetricId");
            var metricMethod = clazz.getMethod("metric");
            var argumentsMethod = clazz.getMethod("arguments");

            String commonMetricId = (String) commonMetricIdMethod.invoke(value);
            String metric = (String) metricMethod.invoke(value);
            @SuppressWarnings("unchecked")
            Map<String, ?> arguments = (Map<String, ?>) argumentsMethod.invoke(value);

            boolean hasCommonMetricId = !StringUtils.isBlank(commonMetricId);
            boolean hasMetric = !StringUtils.isBlank(metric);
            boolean hasArguments = arguments != null && !arguments.isEmpty();

            // Case 1: Both paths are empty - invalid
            if (!hasCommonMetricId && !hasMetric) {
                context.buildConstraintViolationWithTemplate(
                        "Either common_metric_id (for SDK metrics) or metric (for custom Python code) must be provided")
                        .addBeanNode()
                        .addConstraintViolation();
                return false;
            }

            // Case 2: Common metric path (commonMetricId present)
            if (hasCommonMetricId) {
                // When using common metric, metric and arguments should be null/empty
                // This is just a warning case - we allow it but prefer clean separation
                return true;
            }

            // Case 3: Custom metric path (metric present, commonMetricId absent)
            if (hasMetric && !hasArguments) {
                context.buildConstraintViolationWithTemplate(
                        "When using custom Python code (metric), arguments must be provided and non-empty")
                        .addPropertyNode("arguments")
                        .addConstraintViolation();
                return false;
            }

            return true;

        } catch (Exception e) {
            // If reflection fails, something is wrong with the record structure
            context.buildConstraintViolationWithTemplate(
                    "Invalid record structure: " + e.getMessage())
                    .addBeanNode()
                    .addConstraintViolation();
            return false;
        }
    }
}
