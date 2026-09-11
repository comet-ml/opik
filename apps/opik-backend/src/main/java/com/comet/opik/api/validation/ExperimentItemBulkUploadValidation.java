package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {ExperimentItemBulkUploadValidator.class})
@Documented
public @interface ExperimentItemBulkUploadValidation {

    String message() default "trace project_name must match the request project_name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
