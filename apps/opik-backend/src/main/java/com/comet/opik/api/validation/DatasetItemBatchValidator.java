package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemBatch;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DatasetItemBatchValidator implements ConstraintValidator<DatasetItemBatchValidation, DatasetItemBatch> {

    @Override
    public boolean isValid(DatasetItemBatch datasetItemBatch, ConstraintValidatorContext context) {
        return datasetItemBatch.datasetName() != null || datasetItemBatch.datasetId() != null;
    }
}
