package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetVersion(
        @JsonView(DatasetVersion.View.Public.class) UUID id,
        @JsonView(DatasetVersion.View.Public.class) UUID datasetId,
        @JsonView(DatasetVersion.View.Public.class) String versionHash,
        @JsonView(DatasetVersion.View.Public.class) @Nullable List<String> tags,
        @JsonView(DatasetVersion.View.Public.class) @Schema(description = "Total number of items in this version") Integer itemsCount,
        @JsonView(DatasetVersion.View.Public.class) @Schema(description = "Number of items added since last version") Integer itemsAdded,
        @JsonView(DatasetVersion.View.Public.class) @Schema(description = "Number of items modified since last version") Integer itemsModified,
        @JsonView(DatasetVersion.View.Public.class) @Schema(description = "Number of items deleted since last version") Integer itemsDeleted,
        @JsonView(DatasetVersion.View.Public.class) @Nullable String changeDescription,
        @JsonView(DatasetVersion.View.Public.class) @Nullable JsonNode metadata,
        @JsonView(DatasetVersion.View.Public.class) Instant createdAt,
        @JsonView(DatasetVersion.View.Public.class) String createdBy,
        @JsonView(DatasetVersion.View.Public.class) Instant lastUpdatedAt,
        @JsonView(DatasetVersion.View.Public.class) String lastUpdatedBy) {

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }

    public record DatasetVersionPage(
            @JsonView(DatasetVersion.View.Public.class) List<DatasetVersion> content,
            @JsonView(DatasetVersion.View.Public.class) int page,
            @JsonView(DatasetVersion.View.Public.class) int size,
            @JsonView(DatasetVersion.View.Public.class) long total) implements Page<DatasetVersion> {
    }
}
