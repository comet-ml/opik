package com.comet.opik.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

/**
 * Validator for TraceThreadUserDefinedMetricPythonCode records that enforces the union constraint:
 * - Either commonMetricId must be present (SDK/common metric path)
 * - OR metric must be present (custom Python code path)
 *
 * Note: TraceThread metrics don't have arguments field, only metric.
 */
public class TraceThreadUserDefinedMetricCodeValidator
        implements
            ConstraintValidator<TraceThreadUserDefinedMetricCodeValidation, Object> {

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

            String commonMetricId = (String) commonMetricIdMethod.invoke(value);
            String metric = (String) metricMethod.invoke(value);

            boolean hasCommonMetricId = !StringUtils.isBlank(commonMetricId);
            boolean hasMetric = !StringUtils.isBlank(metric);

            // Both paths are empty - invalid
            if (!hasCommonMetricId && !hasMetric) {
                context.buildConstraintViolationWithTemplate(
                        "Either common_metric_id (for SDK metrics) or metric (for custom Python code) must be provided")
                        .addBeanNode()
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
