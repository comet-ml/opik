package com.comet.opik.api.validation;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that TraceThreadUserDefinedMetricPythonCode records have either:
 * - commonMetricId (SDK/common metric path), OR
 * - metric (custom Python code path)
 *
 * Both cannot be null/empty at the same time.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {TraceThreadUserDefinedMetricCodeValidator.class})
@Documented
public @interface TraceThreadUserDefinedMetricCodeValidation {

    String message() default "Either commonMetricId or metric must be provided";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
