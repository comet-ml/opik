package com.comet.opik.api.validation;

import com.comet.opik.api.TraceThreadIdentifier;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TraceThreadIdentifierValidator
        implements
            ConstraintValidator<TraceThreadIdentifierValidation, TraceThreadIdentifier> {

    @Override
    public boolean isValid(TraceThreadIdentifier traceThreadIdentifier,
            ConstraintValidatorContext constraintValidatorContext) {
        return !(traceThreadIdentifier.projectName() == null && traceThreadIdentifier.projectId() == null);
    }
}
