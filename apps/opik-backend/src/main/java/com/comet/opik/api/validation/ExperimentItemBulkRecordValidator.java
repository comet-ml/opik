package com.comet.opik.api.validation;

import com.comet.opik.api.ExperimentItemBulkRecord;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ExperimentItemBulkRecordValidator
        implements
            ConstraintValidator<ExperimentItemBulkRecordValidation, ExperimentItemBulkRecord> {

    private volatile ExperimentItemBulkRecordValidation constraintAnnotation;

    @Override
    public void initialize(ExperimentItemBulkRecordValidation constraintAnnotation) {
        this.constraintAnnotation = constraintAnnotation;
    }

    @Override
    public boolean isValid(ExperimentItemBulkRecord record, ConstraintValidatorContext context) {
        boolean hasEvaluateTaskResult = record.evaluateTaskResult() != null;
        boolean hasTrace = record.trace() != null;

        boolean isInvalid = hasEvaluateTaskResult && hasTrace;

        if (isInvalid) {
            // Disable the default error message
            context.disableDefaultConstraintViolation();

            // Add a custom error message with the exact format we want
            context.buildConstraintViolationWithTemplate(constraintAnnotation.message())
                    .addPropertyNode("<list element>")
                    .addConstraintViolation();
        }

        // Return true if the record is valid (i.e., it does not have both evaluateTaskResult and trace)
        return !isInvalid;
    }
}
