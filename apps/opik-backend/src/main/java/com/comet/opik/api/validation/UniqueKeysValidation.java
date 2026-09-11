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
@Constraint(validatedBy = {UniqueKeysValidator.class})
@Documented
public @interface UniqueKeysValidation {

    String message() default "Duplicate configuration keys are not allowed";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
