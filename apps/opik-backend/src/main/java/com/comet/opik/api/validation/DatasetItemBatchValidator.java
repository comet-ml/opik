package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemBatch;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.UUID;

public class DatasetItemBatchValidator implements ConstraintValidator<DatasetItemBatchValidation, DatasetItemBatch> {

    // OPIK-6696: shared by every endpoint accepting copy_from coordinates so the rule and its error
    // text stay in sync. The DatasetItemChanges endpoint validates the same pair in the service layer.
    public static final String COPY_FROM_PAIR_MESSAGE = "copy_from_dataset_id and copy_from_version_id must be provided together";

    public static boolean isCopyFromPairConsistent(UUID copyFromDatasetId, UUID copyFromVersionId) {
        return (copyFromDatasetId == null) == (copyFromVersionId == null);
    }

    @Override
    public boolean isValid(DatasetItemBatch datasetItemBatch, ConstraintValidatorContext context) {
        if (datasetItemBatch.datasetName() == null && datasetItemBatch.datasetId() == null) {
            return false;
        }

        if (!isCopyFromPairConsistent(datasetItemBatch.copyFromDatasetId(), datasetItemBatch.copyFromVersionId())) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(COPY_FROM_PAIR_MESSAGE)
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
