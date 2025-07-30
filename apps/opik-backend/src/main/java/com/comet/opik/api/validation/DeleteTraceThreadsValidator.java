package com.comet.opik.api.validation;

import com.comet.opik.api.DeleteTraceThreads;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DeleteTraceThreadsValidator
        implements
            ConstraintValidator<DeleteTraceThreadsValidation, DeleteTraceThreads> {

    @Override
    public boolean isValid(DeleteTraceThreads deleteTraceThreads, ConstraintValidatorContext context) {
        return deleteTraceThreads.projectName() != null || deleteTraceThreads.projectId() != null;
    }
}
