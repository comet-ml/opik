package com.comet.opik.api.validate;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Instant;

public class InRangeValidator implements ConstraintValidator<InRange, Instant> {

    public static final String MIN_ANALYTICS_DB = "1970-01-01T00:00:00.000000Z";
    public static final String MAX_ANALYTICS_DB = "2300-01-01T00:00:00.000000Z";
    public static final String MAX_ANALYTICS_DB_PRECISION_9 = "2262-04-11T23:47:16.000000000Z";

    private volatile Instant minInclusive;
    private volatile Instant maxExclusive;

    @Override
    public void initialize(InRange constraintAnnotation) {
        minInclusive = Instant.parse(constraintAnnotation.afterOrEqual());
        maxExclusive = Instant.parse(constraintAnnotation.before());
    }

    @Override
    public boolean isValid(Instant value, ConstraintValidatorContext context) {
        return value == null || (value.compareTo(minInclusive) >= 0 && value.isBefore(maxExclusive));
    }
}
