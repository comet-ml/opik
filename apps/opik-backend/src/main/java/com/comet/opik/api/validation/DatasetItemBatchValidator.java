package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemBatch;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DatasetItemBatchValidator implements ConstraintValidator<DatasetItemBatchValidation, DatasetItemBatch> {

    @Override
    public boolean isValid(DatasetItemBatch datasetItemBatch, ConstraintValidatorContext context) {
        if (datasetItemBatch.datasetName() == null && datasetItemBatch.datasetId() == null) {
            return false;
        }

        // OPIK-6696: copy_from_dataset_id and copy_from_version_id must be set together (or both null).
        boolean hasCopyFromDataset = datasetItemBatch.copyFromDatasetId() != null;
        boolean hasCopyFromVersion = datasetItemBatch.copyFromVersionId() != null;
        if (hasCopyFromDataset != hasCopyFromVersion) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "copy_from_dataset_id and copy_from_version_id must be provided together")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
