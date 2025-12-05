package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.SequencedSet;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Summary of the latest dataset version")
public record DatasetVersionSummary(
        @Schema(description = "Unique identifier of the version") UUID id,
        @Schema(description = "Hash of the version content") String versionHash,
        @Schema(description = "Description of changes in this version") String changeDescription,
        @Schema(description = "Tags associated with this version") SequencedSet<String> tags) {

    public static DatasetVersionSummary from(DatasetVersion version) {
        if (version == null) {
            return null;
        }
        return DatasetVersionSummary.builder()
                .id(version.id())
                .versionHash(version.versionHash())
                .changeDescription(version.changeDescription())
                .tags(version.tags())
                .build();
    }
}
