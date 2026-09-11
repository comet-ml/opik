package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemsDelete;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.collections4.CollectionUtils;

public class DatasetItemsDeleteValidator
        implements
            ConstraintValidator<DatasetItemsDeleteValidation, DatasetItemsDelete> {

    @Override
    public boolean isValid(DatasetItemsDelete deleteRequest, ConstraintValidatorContext context) {
        if (deleteRequest == null) {
            return false;
        }

        context.disableDefaultConstraintViolation();

        boolean hasItemIds = CollectionUtils.isNotEmpty(deleteRequest.itemIds());
        boolean hasDatasetId = deleteRequest.datasetId() != null;
        boolean hasFilters = deleteRequest.filters() != null;

        // Case 1: Delete by IDs (itemIds only)
        if (hasItemIds) {
            if (hasDatasetId || hasFilters) {
                context.buildConstraintViolationWithTemplate(
                        "Cannot provide 'dataset_id' or 'filters' when using 'item_ids'. Use 'item_ids' alone to delete specific items.")
                        .addConstraintViolation();
                return false;
            }
            return true;
        }

        // Case 2: Delete by filters (datasetId required, filters optional)
        if (hasDatasetId) {
            // Filters are optional when dataset_id is provided
            return true;
        }

        // Case 3: Invalid - neither mode specified
        context.buildConstraintViolationWithTemplate(
                "Either 'item_ids' (to delete specific items) or 'dataset_id' (to delete by filters) must be provided.")
                .addConstraintViolation();
        return false;
    }
}
