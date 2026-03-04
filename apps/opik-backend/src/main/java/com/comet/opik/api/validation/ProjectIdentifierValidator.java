package com.comet.opik.api.validation;

import com.comet.opik.api.AgentConfigCreate;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ProjectIdentifierValidator implements ConstraintValidator<ProjectIdentifierValidation, AgentConfigCreate> {

    @Override
    public boolean isValid(AgentConfigCreate request, ConstraintValidatorContext context) {
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
