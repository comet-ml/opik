package com.comet.opik.api.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "A generated view and the version that renders")
public record View(
        UUID id,
        ViewArea area,
        String name,
        @Nullable String description,
        int currentVersion,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy,
        @Nullable @Schema(description = "The current version") ViewVersion version) {
}
