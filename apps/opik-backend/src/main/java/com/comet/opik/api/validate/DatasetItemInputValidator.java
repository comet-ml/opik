package com.comet.opik.api.validate;

import com.comet.opik.api.DatasetItem;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DatasetItemInputValidator implements ConstraintValidator<DatasetItemInputValidation, DatasetItem> {

    @Override
    public boolean isValid(DatasetItem datasetItem, ConstraintValidatorContext context) {
        boolean result = datasetItem.input() != null || (datasetItem.inputData() != null && !datasetItem.inputData().isEmpty());

        if (!result) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("must provide either input or input_data")
                    .addPropertyNode("input")
                    .addConstraintViolation();
        }

        return result;
    }
}
