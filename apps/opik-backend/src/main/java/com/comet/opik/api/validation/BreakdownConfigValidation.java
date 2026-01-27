package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {BreakdownConfigValidator.class})
@Documented
public @interface BreakdownConfigValidation {

    String message() default "Invalid breakdown configuration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
