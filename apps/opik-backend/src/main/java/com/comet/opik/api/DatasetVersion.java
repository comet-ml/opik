package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetVersion(
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String versionHash,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) SequencedSet<String> tags,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Indicates whether this is the latest version of the dataset") boolean isLatest,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Sequential version name formatted as 'v1', 'v2', etc.") String versionName,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Total number of items in this version") Integer itemsTotal,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Number of items added since last version") Integer itemsAdded,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Number of items modified since last version") Integer itemsModified,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Number of items deleted since last version") Integer itemsDeleted,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String changeDescription,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, String> metadata,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Default evaluators for items in this version") List<EvaluatorItem> evaluators,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Default execution policy for items in this version") ExecutionPolicy executionPolicy,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView(DatasetVersion.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

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
