package com.comet.opik.api.validation;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxJsonSizeValidator implements ConstraintValidator<MaxJsonSize, JsonNode> {

    private volatile long maxSizeInBytes;

    @Override
    public void initialize(MaxJsonSize constraintAnnotation) {
        this.maxSizeInBytes = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        long size = JsonUtils.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        return size <= maxSizeInBytes;
    }
}
