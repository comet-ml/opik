package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Comment(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @NotBlank String text,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {
}
