package com.comet.opik.api;

import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.validation.DatasetItemBatchUpdateValidation;
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
@DatasetItemBatchUpdateValidation
@Schema(description = "Request to batch update multiple dataset items")
public record DatasetItemBatchUpdate(
        @Size(min = 1, max = 1000) @Schema(description = "List of dataset item IDs to update (max 1000). Mutually exclusive with 'filters'.") Set<UUID> ids,
        @Valid @Schema(description = "List of filters to match dataset items for update. Mutually exclusive with 'ids'.") List<DatasetItemFilter> filters,
        @NotNull @Valid @Schema(description = "Update to apply to all dataset items", required = true) DatasetItemUpdate update,
        @Schema(description = "If true, merge tags with existing tags instead of replacing them. Default: false") Boolean mergeTags) {
}
