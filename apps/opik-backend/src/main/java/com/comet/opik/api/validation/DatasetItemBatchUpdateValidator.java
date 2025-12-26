package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemBatchUpdate;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.collections4.CollectionUtils;

public class DatasetItemBatchUpdateValidator
        implements
            ConstraintValidator<DatasetItemBatchUpdateValidation, DatasetItemBatchUpdate> {

    @Override
    public boolean isValid(DatasetItemBatchUpdate batchUpdate, ConstraintValidatorContext context) {
        if (batchUpdate == null) {
            return false;
        }

        context.disableDefaultConstraintViolation();

        // Check if `ids` is provided
        boolean hasIds = CollectionUtils.isNotEmpty(batchUpdate.ids());
        // Check if filters are provided (empty array means "select all items", null means not provided)
        boolean hasFilters = batchUpdate.filters() != null;

        // Validate that at least one of ids or filters is provided
        if (!hasIds && !hasFilters) {
            context.buildConstraintViolationWithTemplate("Either 'ids' or 'filters' must be provided.")
                    .addConstraintViolation();
            return false;
        }

        // Validate that both ids and filters are not provided at the same time
        if (hasIds && hasFilters) {
            context.buildConstraintViolationWithTemplate(
                    "Cannot provide both 'ids' and 'filters'. Use 'ids' for specific items or 'filters' to update items matching the filter criteria.")
                    .addConstraintViolation();
            return false;
        }

        // Validate that datasetId is provided when using filters
        if (hasFilters && batchUpdate.datasetId() == null) {
            context.buildConstraintViolationWithTemplate(
                    "'dataset_id' is required when using 'filters'. This ensures updates are scoped to a specific dataset.")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
