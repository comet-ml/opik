package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.filter.Operator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.collections4.CollectionUtils;

import java.util.UUID;

public class DatasetItemsDeleteValidator
        implements
            ConstraintValidator<DatasetItemsDeleteValidation, DatasetItemsDelete> {

    private static final String DATASET_ID_FIELD = "dataset_id";

    @Override
    public boolean isValid(DatasetItemsDelete deleteRequest, ConstraintValidatorContext context) {
        if (deleteRequest == null) {
            return false;
        }

        context.disableDefaultConstraintViolation();

        // Check if `item_ids` is provided
        boolean hasItemIds = CollectionUtils.isNotEmpty(deleteRequest.itemIds());
        // Check if filters are provided (must contain at least one filter)
        boolean hasFilters = CollectionUtils.isNotEmpty(deleteRequest.filters());

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

        // CRITICAL SECURITY CHECK: When using filters, dataset_id must be present
        if (hasFilters) {
            boolean hasDatasetIdFilter = deleteRequest.filters().stream()
                    .anyMatch(filter -> DATASET_ID_FIELD.equals(filter.field().getQueryParamField())
                            && Operator.EQUAL.equals(filter.operator())
                            && isValidUUID(filter.value()));

            if (!hasDatasetIdFilter) {
                context.buildConstraintViolationWithTemplate(
                        "When using 'filters', a dataset_id filter with operator '=' and a valid UUID value must be provided to scope the deletion to a specific dataset.")
                        .addConstraintViolation();
                return false;
            }
        }

        return true;
    }

    /**
     * Validates if the given value is a valid UUID string.
     */
    private boolean isValidUUID(Object value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value.toString());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
