package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Request payload for applying delta changes to a dataset version.
 * This endpoint is designed for the UI to submit changes with version metadata
 * and conflict handling via baseVersion.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetItemChanges(
        @JsonView(DatasetItemChanges.View.Write.class) @Schema(description = "New items to add to the dataset") @Valid List<DatasetItem> addedItems,

        @JsonView(DatasetItemChanges.View.Write.class) @Schema(description = "Existing items to update with partial data (matched by id)") @Valid List<DatasetItemEdit> editedItems,

        @JsonView(DatasetItemChanges.View.Write.class) @Schema(description = "Item IDs to delete from the dataset") Set<UUID> deletedIds,

        @JsonView(DatasetItemChanges.View.Write.class) @NotNull @Schema(description = "Version ID the client is editing (for conflict detection)", required = true) UUID baseVersion,

        @JsonView(DatasetItemChanges.View.Write.class) @Valid @Schema(description = "Optional list of tags for this version", example = "[\"baseline\", \"v1.0\"]") List<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Each tag must be at most 100 characters") String> tags,

        @JsonView(DatasetItemChanges.View.Write.class) @Schema(description = "Optional description of changes in this version", example = "Updated training data with corrections") String changeDescription,

        @JsonView(DatasetItemChanges.View.Write.class) @Schema(description = "Optional user-defined metadata") Map<String, String> metadata) {

    public static class View {
        public static class Write {
        }
    }
}
