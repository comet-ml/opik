package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated {@code com.fasterxml.jackson.databind.JsonNode} must serialize to at most {@code value} UTF-8 bytes.
 *
 * <p>
 * {@code null} elements are considered valid.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxJsonSizeValidator.class)
public @interface MaxJsonSize {

    String message() default "exceeds the maximum allowed size of {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * @return the maximum serialized size in bytes
     */
    long value();
}
