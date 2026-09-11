package com.comet.opik.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ProjectIdentifierValidator
        implements
            ConstraintValidator<ProjectIdentifierValidation, HasProjectIdentifier> {

    @Override
    public boolean isValid(HasProjectIdentifier request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }

        if (request.projectId() == null && request.projectName() == null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Either projectId or projectName must be provided")
                    .addBeanNode()
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
