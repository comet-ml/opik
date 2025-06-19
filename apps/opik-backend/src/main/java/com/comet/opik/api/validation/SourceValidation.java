package com.comet.opik.api.validation;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {SourceValidator.class})
@Documented
public @interface SourceValidation {

    String message() default "";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
