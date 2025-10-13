package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizationStudioRun(
        @JsonView({View.Public.class, View.Write.class}) UUID id,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank String name,
        @JsonView({View.Public.class, View.Write.class}) @NotNull UUID datasetId,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank String datasetName,
        @JsonView({View.Public.class, View.Write.class}) UUID optimizationId,
        @JsonView({View.Public.class, View.Write.class}) @NotNull List<OptimizationStudioPromptMessage> prompt,
        @JsonView({View.Public.class, View.Write.class}) @NotNull OptimizationAlgorithm algorithm,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank String metric,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) OptimizationStudioRunStatus status,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
