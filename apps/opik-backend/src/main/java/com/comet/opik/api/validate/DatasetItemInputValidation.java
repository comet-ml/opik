package com.comet.opik.api.validate;

import jakarta.validation.Constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {DatasetItemInputValidator.class})
@Documented
public @interface DatasetItemInputValidation {

    String message() default "must provide either input or data field";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
