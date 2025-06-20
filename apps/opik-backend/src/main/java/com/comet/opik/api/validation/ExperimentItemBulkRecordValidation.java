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
@Constraint(validatedBy = {ExperimentItemBulkRecordValidator.class})
@Documented
public @interface ExperimentItemBulkRecordValidation {

    String message() default "cannot provide both evaluate_task_result and trace together";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
