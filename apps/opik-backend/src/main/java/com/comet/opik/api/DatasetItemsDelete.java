package com.comet.opik.api;

import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.validation.DatasetItemsDeleteValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@DatasetItemsDeleteValidation
@Schema(description = "Request to delete multiple dataset items")
public record DatasetItemsDelete(
        @Size(min = 1, max = 1000) @Schema(description = "List of dataset item IDs to delete (max 1000). Use this to delete specific items by their IDs. Mutually exclusive with 'dataset_id' and 'filters'.") Set<@NotNull UUID> itemIds,
        @Schema(description = "Dataset ID to scope the deletion. Required when using 'filters'. Mutually exclusive with 'item_ids'.") UUID datasetId,
        @Valid @Schema(description = "Filters to select dataset items to delete within the specified dataset. Must be used with 'dataset_id'. Mutually exclusive with 'item_ids'. Empty array means 'delete all items in the dataset'.") List<@NotNull @Valid DatasetItemFilter> filters,
        @Schema(description = "Optional batch group ID to group multiple delete operations into a single dataset version. If null, mutates the latest version instead of creating a new one.") UUID batchGroupId) {
}
