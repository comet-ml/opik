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
import org.apache.commons.collections4.CollectionUtils;

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
        @Valid List<@NotNull @Valid DatasetItemFilter> filters,
        @Schema(description = "Dataset ID. Required when using 'filters', optional when using 'ids'.") UUID datasetId,
        @NotNull @Valid @Schema(description = "Update to apply to all dataset items", required = true) DatasetItemUpdate update,
        @Schema(description = "If true, merge tags with existing tags instead of replacing them. Default: false. When using 'filters', this is automatically set to true.") Boolean mergeTags) {

    /**
     * Override the mergeTags accessor to automatically set it to true when filters are provided.
     * This ensures filter-based updates always merge tags rather than replace them.
     */
    @Override
    public Boolean mergeTags() {
        if (CollectionUtils.isNotEmpty(filters)) {
            return true;
        }
        return mergeTags != null ? mergeTags : false;
    }
}
