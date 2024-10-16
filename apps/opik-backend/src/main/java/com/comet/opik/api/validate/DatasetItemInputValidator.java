package com.comet.opik.api.validate;

import com.comet.opik.api.DatasetItem;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.collections4.MapUtils;

import java.util.Map;
import java.util.Optional;

public class DatasetItemInputValidator implements ConstraintValidator<DatasetItemInputValidation, DatasetItem> {

    @Override
    public boolean isValid(DatasetItem datasetItem, ConstraintValidatorContext context) {
        boolean result = datasetItem.input() != null || MapUtils.isNotEmpty(datasetItem.data());

        if (!result) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("must provide either input or data field")
                    .addPropertyNode("input")
                    .addConstraintViolation();
        }

        if (result && datasetItem.data() != null) {
            Optional<Map.Entry<String, JsonNode>> error = datasetItem.data().entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() == null)
                    .findAny();

            if (error.isPresent()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("field must not contain null key or value")
                        .addPropertyNode("data")
                        .addConstraintViolation();
            }

            result = error.isEmpty();
        }

        return result;
    }
}
