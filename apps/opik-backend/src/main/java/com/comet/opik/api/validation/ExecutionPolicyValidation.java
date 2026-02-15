package com.comet.opik.api.validation;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {ExecutionPolicyValidator.class})
@Documented
public @interface ExecutionPolicyValidation {

    String message() default "pass_threshold must be less than or equal to runs_per_item";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
