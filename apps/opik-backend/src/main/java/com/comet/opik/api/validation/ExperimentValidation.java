package com.comet.opik.api.validation;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {ExperimentValidator.class})
@Documented
public @interface ExperimentValidation {

    String message() default "";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
