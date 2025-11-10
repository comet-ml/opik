package com.comet.opik.api.validation;

import com.comet.opik.api.TraceThreadBatchIdentifier;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

public class TraceThreadBatchIdentifierValidator
        implements
            ConstraintValidator<TraceThreadBatchIdentifierValidation, TraceThreadBatchIdentifier> {

    @Override
    public boolean isValid(TraceThreadBatchIdentifier identifier, ConstraintValidatorContext context) {
        if (identifier == null) {
            return false;
        }

        boolean isValid = true;
        context.disableDefaultConstraintViolation();

        // Validate project identifier (projectName OR projectId)
        boolean hasProjectName = StringUtils.isNotBlank(identifier.projectName());
        boolean hasProjectId = identifier.projectId() != null;

        if (!hasProjectName && !hasProjectId) {
            context.buildConstraintViolationWithTemplate("Either 'projectName' or 'projectId' must be provided.")
                    .addConstraintViolation();
            isValid = false;
        }

        // Validate thread identifiers (threadIds not null OR threadId not blank)
        // Note: We check for null threadIds, not empty, because @Size annotation handles empty list validation
        boolean hasThreadIds = identifier.threadIds() != null;
        boolean hasThreadId = StringUtils.isNotBlank(identifier.threadId());

        if (!hasThreadIds && !hasThreadId) {
            context.buildConstraintViolationWithTemplate("Either 'threadId' or 'threadIds' must be provided.")
                    .addConstraintViolation();
            isValid = false;
        }

        // Validate that both threadId and threadIds are not provided at the same time
        if (hasThreadId && hasThreadIds) {
            context.buildConstraintViolationWithTemplate(
                    "Cannot provide both 'threadId' and 'threadIds'. Use 'threadId' for single operations or 'threadIds' for batch operations.")
                    .addConstraintViolation();
            isValid = false;
        }

        return isValid;
    }
}
