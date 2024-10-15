package com.comet.opik.api.validate;

import com.comet.opik.api.DatasetItem;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.collections4.MapUtils;

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

        if (datasetItem.data() != null) {
            datasetItem.data().forEach((key, value) -> {
                if (value == null) {
                    context.disableDefaultConstraintViolation();
                    context.buildConstraintViolationWithTemplate("must not contain null values")
                            .addPropertyNode("data")
                            .addPropertyNode(key)
                            .addConstraintViolation();
                }
            });
        }

        return result;
    }
}
