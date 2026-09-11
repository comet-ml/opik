package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {ProviderAuthCheckValidator.class})
@Documented
public @interface ProviderAuthCheckValidation {

    String message() default "invalid auth check request";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
