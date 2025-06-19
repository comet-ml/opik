package com.comet.opik.api.validation;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {DeleteTraceThreadsValidator.class})
@Documented
public @interface DeleteTraceThreadsValidation {

    String message() default "must provide either a project_name or a project_id";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
