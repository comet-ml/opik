package com.comet.opik.api.validation;

import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.Trace;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

public class ExperimentItemBulkUploadValidator
        implements
            ConstraintValidator<ExperimentItemBulkUploadValidation, ExperimentItemBulkUpload> {

    private volatile ExperimentItemBulkUploadValidation constraintAnnotation;

    @Override
    public void initialize(ExperimentItemBulkUploadValidation constraintAnnotation) {
        this.constraintAnnotation = constraintAnnotation;
    }

    @Override
    public boolean isValid(ExperimentItemBulkUpload upload, ConstraintValidatorContext context) {
        // No request-level project_name: item-level trace projects are unconstrained.
        if (upload == null || StringUtils.isBlank(upload.projectName()) || CollectionUtils.isEmpty(upload.items())) {
            return true;
        }

        // When a request-level project_name is provided, it is authoritative for the whole upload.
        // An item-level trace pointing at a different project would split the experiment's traces across
        // projects, so reject the contradiction.
        boolean hasMismatch = upload.items().stream()
                .map(ExperimentItemBulkRecord::trace)
                .filter(Objects::nonNull)
                .map(Trace::projectName)
                .filter(StringUtils::isNotBlank)
                .anyMatch(traceProjectName -> !traceProjectName.equalsIgnoreCase(upload.projectName()));

        if (hasMismatch) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(constraintAnnotation.message())
                    .addPropertyNode("items")
                    .addConstraintViolation();
        }

        return !hasMismatch;
    }
}
