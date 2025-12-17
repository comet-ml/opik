package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.filter.DatasetItemField;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.Operator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DatasetItemsDeleteValidator Tests")
class DatasetItemsDeleteValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @Test
    @DisplayName("Valid: Delete by itemIds only")
    void validateWhenValidItemIdsProvided() {
        // Given
        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(UUID.randomUUID(), UUID.randomUUID()))
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Delete by datasetId only (all items in dataset)")
    void validateWhenDatasetIdOnlyProvided() {
        // Given - Only dataset_id (selects all items in that dataset)
        var datasetId = UUID.randomUUID();
        var deleteRequest = DatasetItemsDelete.builder()
                .datasetId(datasetId)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Delete by datasetId with filters")
    void validateWhenDatasetIdWithFiltersProvided() {
        // Given
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field(DatasetItemField.TAGS)
                        .operator(Operator.EQUAL)
                        .value("test")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .datasetId(datasetId)
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Delete by datasetId with multiple filters")
    void validateWhenDatasetIdWithMultipleFiltersProvided() {
        // Given - Multiple filters with dataset_id
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field(DatasetItemField.TAGS)
                        .operator(Operator.CONTAINS)
                        .value("test")
                        .build(),
                DatasetItemFilter.builder()
                        .field(DatasetItemField.CREATED_AT)
                        .operator(Operator.GREATER_THAN)
                        .value("2024-01-01")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .datasetId(datasetId)
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Delete by datasetId with empty filters array")
    void validateWhenDatasetIdWithEmptyFiltersProvided() {
        // Given - Empty filters array with dataset_id means delete all items in dataset
        var datasetId = UUID.randomUUID();
        var deleteRequest = DatasetItemsDelete.builder()
                .datasetId(datasetId)
                .filters(List.of())
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Invalid: Both itemIds and datasetId provided")
    void validateWhenBothItemIdsAndDatasetIdProvided() {
        // Given
        var datasetId = UUID.randomUUID();
        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(UUID.randomUUID()))
                .datasetId(datasetId)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("Cannot provide 'dataset_id' or 'filters' when using 'item_ids'");
    }

    @Test
    @DisplayName("Invalid: Both itemIds and filters provided")
    void validateWhenBothItemIdsAndFiltersProvided() {
        // Given
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field(DatasetItemField.TAGS)
                        .operator(Operator.EQUAL)
                        .value("test")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(UUID.randomUUID()))
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("Cannot provide 'dataset_id' or 'filters' when using 'item_ids'");
    }

    @Test
    @DisplayName("Invalid: All three fields provided")
    void validateWhenAllFieldsProvided() {
        // Given
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field(DatasetItemField.TAGS)
                        .operator(Operator.EQUAL)
                        .value("test")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(UUID.randomUUID()))
                .datasetId(datasetId)
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("Cannot provide 'dataset_id' or 'filters' when using 'item_ids'");
    }

    @Test
    @DisplayName("Invalid: Neither itemIds nor datasetId provided")
    void validateWhenNeitherItemIdsNorDatasetIdProvided() {
        // Given
        var deleteRequest = DatasetItemsDelete.builder().build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("Either 'item_ids'")
                .contains("or 'dataset_id'")
                .contains("must be provided");
    }

    @Test
    @DisplayName("Invalid: Only filters provided without datasetId")
    void validateWhenOnlyFiltersProvided() {
        // Given - Filters without dataset_id
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field(DatasetItemField.TAGS)
                        .operator(Operator.EQUAL)
                        .value("test")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("Either 'item_ids'")
                .contains("or 'dataset_id'")
                .contains("must be provided");
    }

    @Test
    @DisplayName("Invalid: Empty filters array without datasetId")
    void validateWhenEmptyFiltersArrayWithoutDatasetId() {
        // Given - Empty filters array without dataset_id
        var deleteRequest = DatasetItemsDelete.builder()
                .filters(List.of())
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("Either 'item_ids'")
                .contains("or 'dataset_id'")
                .contains("must be provided");
    }
}
