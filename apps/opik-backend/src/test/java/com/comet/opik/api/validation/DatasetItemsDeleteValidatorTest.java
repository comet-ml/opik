package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

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
    @DisplayName("Valid: Delete by filters with dataset_id")
    void validateWhenValidFiltersWithDatasetId() {
        // Given
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value(datasetId.toString())
                        .build(),
                DatasetItemFilter.builder()
                        .field("tags")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value("test")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid: Empty filters array with dataset_id (select all items in dataset)")
    void validateWhenEmptyFiltersWithDatasetId() {
        // Given - Empty array means "select all items" if dataset_id is present
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value(datasetId.toString())
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Invalid: Both itemIds and filters provided")
    void validateWhenBothItemIdsAndFiltersProvided() {
        // Given
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value(datasetId.toString())
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
                .isEqualTo(
                        "Cannot provide both 'item_ids' and 'filters'. Use 'item_ids' for specific items or 'filters' to delete items matching the filter criteria.");
    }

    @Test
    @DisplayName("Invalid: Neither itemIds nor filters provided")
    void validateWhenNeitherItemIdsNorFiltersProvided() {
        // Given
        var deleteRequest = DatasetItemsDelete.builder().build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Either 'item_ids' or 'filters' must be provided.");
    }

    @Test
    @DisplayName("Invalid: Filters without dataset_id")
    void validateWhenFiltersWithoutDatasetId() {
        // Given - No dataset_id filter (SECURITY ISSUE!)
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("tags")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
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
                .contains("dataset_id filter")
                .contains("must be provided");
    }

    @Test
    @DisplayName("Invalid: Filters with dataset_id but wrong operator")
    void validateWhenFiltersWithDatasetIdButWrongOperator() {
        // Given - dataset_id with wrong operator (!= instead of =)
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.NOT_EQUAL) // Wrong operator!
                        .type(FieldType.STRING)
                        .value(datasetId.toString())
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("operator '='");
    }

    @Test
    @DisplayName("Invalid: Filters with dataset_id but invalid UUID")
    void validateWhenFiltersWithDatasetIdButInvalidUUID() {
        // Given - dataset_id with non-UUID value
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value("not-a-valid-uuid") // Invalid UUID!
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("valid UUID");
    }

    @Test
    @DisplayName("Invalid: Filters with dataset_id but null value")
    void validateWhenFiltersWithDatasetIdButNullValue() {
        // Given - dataset_id with null value
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value(null) // Null value!
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("valid UUID");
    }

    @ParameterizedTest
    @MethodSource("invalidOperatorCases")
    @DisplayName("Invalid: Filters with dataset_id but unsupported operators")
    void validateWhenFiltersWithDatasetIdButUnsupportedOperators(Operator operator, String description) {
        // Given - dataset_id with various unsupported operators
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(operator)
                        .type(FieldType.STRING)
                        .value(datasetId.toString())
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).hasSize(1)
                .withFailMessage("Operator %s (%s) should not be allowed for dataset_id", operator, description);
    }

    @Test
    @DisplayName("Valid: Multiple filters including dataset_id")
    void validateWhenMultipleFiltersIncludingDatasetId() {
        // Given - Multiple filters with dataset_id present
        var datasetId = UUID.randomUUID();
        var filters = List.of(
                DatasetItemFilter.builder()
                        .field("dataset_id")
                        .operator(Operator.EQUAL)
                        .type(FieldType.STRING)
                        .value(datasetId.toString())
                        .build(),
                DatasetItemFilter.builder()
                        .field("tags")
                        .operator(Operator.CONTAINS)
                        .type(FieldType.STRING)
                        .value("test")
                        .build(),
                DatasetItemFilter.builder()
                        .field("created_at")
                        .operator(Operator.GREATER_THAN)
                        .type(FieldType.STRING)
                        .value("2024-01-01")
                        .build());

        var deleteRequest = DatasetItemsDelete.builder()
                .filters(filters)
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Invalid: itemIds with null UUID")
    void validateWhenItemIdsContainNull() {
        // Given - This should be caught by @NotNull on Set elements
        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(UUID.randomUUID(), null))
                .build();

        // When
        Set<ConstraintViolation<DatasetItemsDelete>> violations = validator.validate(deleteRequest);

        // Then
        assertThat(violations).isNotEmpty();
        assertThat(violations.stream().anyMatch(v -> v.getMessage().contains("must not be null"))).isTrue();
    }

    private static Stream<Arguments> invalidOperatorCases() {
        return Stream.of(
                Arguments.of(Operator.NOT_EQUAL, "not equal - would delete from all other datasets"),
                Arguments.of(Operator.IN, "IN operator - could delete from multiple datasets"),
                Arguments.of(Operator.NOT_IN, "NOT IN operator - too broad"),
                Arguments.of(Operator.CONTAINS, "CONTAINS - not precise enough"),
                Arguments.of(Operator.NOT_CONTAINS, "NOT CONTAINS - too broad"),
                Arguments.of(Operator.GREATER_THAN, "GREATER THAN - not applicable to dataset_id"),
                Arguments.of(Operator.GREATER_THAN_OR_EQUAL, "GREATER THAN OR EQUAL - not applicable"),
                Arguments.of(Operator.LESS_THAN, "LESS THAN - not applicable"),
                Arguments.of(Operator.LESS_THAN_OR_EQUAL, "LESS THAN OR EQUAL - not applicable"));
    }
}
