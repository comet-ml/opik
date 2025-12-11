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
        @Size(min = 1, max = 1000) @Schema(description = "List of dataset item IDs to delete (max 1000). Mutually exclusive with 'filters'.") Set<@NotNull UUID> itemIds,
        @Valid @Schema(description = "Filters to select dataset items to delete. Mutually exclusive with 'item_ids'. Empty array means 'select all items'.") List<@NotNull @Valid DatasetItemFilter> filters) {
}
