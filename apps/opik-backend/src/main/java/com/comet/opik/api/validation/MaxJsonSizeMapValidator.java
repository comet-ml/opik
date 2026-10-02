package com.comet.opik.api.validation;

import com.comet.opik.utils.JsonUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;

/**
 * {@link MaxJsonSize} support for fields typed as {@link Map} rather than
 * {@link com.fasterxml.jackson.databind.JsonNode}.
 *
 * <p>
 * Nesting depth does not need a separate guard here: Jackson rejects documents deeper than its
 * {@code StreamReadConstraints} limit while deserializing the request, so a map that reaches validation is
 * already depth-bounded.
 */
public class MaxJsonSizeMapValidator implements ConstraintValidator<MaxJsonSize, Map<?, ?>> {

    private volatile long maxSizeInBytes;

    @Override
    public void initialize(MaxJsonSize constraintAnnotation) {
        this.maxSizeInBytes = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(Map<?, ?> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return !JsonUtils.exceedsSerializedLengthInBytes(value, maxSizeInBytes);
    }
}
