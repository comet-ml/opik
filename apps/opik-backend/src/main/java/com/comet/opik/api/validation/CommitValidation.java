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
@Constraint(validatedBy = {CommitValidator.class})
@Documented
public @interface CommitValidation {

    String message() default "if present, the commit message must be 8 alphanumeric characters long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
