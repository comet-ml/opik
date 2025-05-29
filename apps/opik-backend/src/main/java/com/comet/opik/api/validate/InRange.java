package com.comet.opik.api.validate;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated element must be an ISO-8601 instant in UTC:
 * <ul>
 *     <li>{@code java.time.Instant}</li>
 * </ul>
 *
 * <p>
 * {@code null} elements are considered valid.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = InRangeValidator.class)
public @interface InRange {

    String message() default "must be after or equal {afterOrEqual} and before {before}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * Defaults to the epoch.
     *
     * @return the element must be after or equal to
     */
    String afterOrEqual() default InRangeValidator.MIN_ANALYTICS_DB;

    /**
     * Defaults to the maximum DateTime64 value in ClickHouse when using precision of 9 digits (nanoseconds):
     * <a href="https://clickhouse.com/docs/sql-reference/data-types/datetime64">ClickHouse DateTime64</a>
     *
     * @return the element must be before
     */
    String before() default InRangeValidator.MAX_ANALYTICS_DB_PRECISION_9;
}
