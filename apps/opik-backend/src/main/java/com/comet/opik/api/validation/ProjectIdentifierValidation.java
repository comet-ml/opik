package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {ProjectIdentifierValidator.class})
@Documented
public @interface ProjectIdentifierValidation {

    String message() default "Either projectId or projectName must be provided";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
