package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the total size of a request does not exceed a specified maximum.
 */
@Documented
@Constraint(validatedBy = MaxRequestSizeValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxRequestSize {
    String message() default "Request size exceeds the maximum allowed size";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /**
     * Maximum size in bytes
     */
    long value() default 4 * 1024 * 1024; // 4MB default
}
