package com.comet.opik.api.validation;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.temporal.ChronoUnit;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {DurationValidator.class})
@Documented
public @interface DurationValidation {

    String message() default "minimum precision supported is seconds, please use a duration with seconds precision or higher";

    int max() default 7; // Default maximum duration in days

    ChronoUnit unit() default ChronoUnit.DAYS;

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};

}
