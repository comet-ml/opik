package com.comet.opik.api.validation;

import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.utils.JsonUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;

/**
 * Validator that checks if the total size of a request exceeds the maximum allowed size.
 * This uses JSON serialization to estimate the total size of the request.
 */
@Slf4j
public class MaxRequestSizeValidator implements ConstraintValidator<MaxRequestSize, ExperimentItemBulkUpload> {

    private volatile long maxSizeInBytes;

    @Override
    public void initialize(MaxRequestSize constraintAnnotation) {
        this.maxSizeInBytes = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(ExperimentItemBulkUpload value, ConstraintValidatorContext context) {
        if (value == null || value.items() == null) {
            return true; // Let @NotNull handle this
        }

        try {
            // Serialize the object to JSON to get an accurate size estimate
            byte[] serialized = JsonUtils.writeValueAsString(value).getBytes();
            long size = serialized.length;

            boolean isValid = size <= maxSizeInBytes;

            if (!isValid) {
                // Add a custom violation message
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "Request size exceeds the maximum allowed size of " + (maxSizeInBytes / (1024 * 1024)) + "MB")
                        .addConstraintViolation();
            }

            return isValid;
        } catch (UncheckedIOException e) {
            log.error("Error serializing request for size validation", e);
            return false;
        }
    }
}
