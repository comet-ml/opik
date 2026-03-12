package com.comet.opik.api.validation;

import com.comet.opik.api.ExecutionPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ExecutionPolicyValidator
        implements
            ConstraintValidator<ExecutionPolicyValidation, ExecutionPolicy> {

    @Override
    public boolean isValid(ExecutionPolicy executionPolicy,
            ConstraintValidatorContext constraintValidatorContext) {
        if (executionPolicy == null) {
            return true;
        }
        return executionPolicy.passThreshold() <= executionPolicy.runsPerItem();
    }
}
