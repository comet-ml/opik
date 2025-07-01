package com.comet.opik.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;

public class DurationValidator implements ConstraintValidator<DurationValidation, Duration> {

    private volatile Duration maxDuration;

    @Override
    public void initialize(DurationValidation constraintAnnotation) {
        maxDuration = Duration.of(constraintAnnotation.max(), constraintAnnotation.unit());
    }

    @Override
    public boolean isValid(Duration duration, ConstraintValidatorContext context) {
        if (duration == null) {
            return true; // Null values are considered valid
        }

        // Check if the duration has precision equal to or greater than seconds
        boolean validDuration = !(duration.getNano() > 0 || duration.getSeconds() == 0);

        if (validDuration && duration.compareTo(maxDuration) > 0) {

            validDuration = false; // Ensure the duration does not exceed the maximum allowed

            // Disable the default error message
            context.disableDefaultConstraintViolation();

            // Add a custom error message with the exact format we want
            context.buildConstraintViolationWithTemplate(
                    "duration exceeds the maximum allowed of " + maxDuration.toString())
                    .addConstraintViolation();
        }

        return validDuration;
    }
}
