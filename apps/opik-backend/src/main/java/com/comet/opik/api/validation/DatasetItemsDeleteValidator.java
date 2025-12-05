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

        // Check if `item_ids` is provided
        boolean hasItemIds = CollectionUtils.isNotEmpty(deleteRequest.itemIds());
        // Check if filters are provided (empty array means "select all items", null means not provided)
        boolean hasFilters = deleteRequest.filters() != null;

        // Validate that at least one of item_ids or filters is provided
        if (!hasItemIds && !hasFilters) {
            context.buildConstraintViolationWithTemplate("Either 'item_ids' or 'filters' must be provided.")
                    .addConstraintViolation();
            return false;
        }

        // Validate that both item_ids and filters are not provided at the same time
        if (hasItemIds && hasFilters) {
            context.buildConstraintViolationWithTemplate(
                    "Cannot provide both 'item_ids' and 'filters'. Use 'item_ids' for specific items or 'filters' to delete items matching the filter criteria.")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
