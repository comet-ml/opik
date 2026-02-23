package com.comet.opik.api;

import com.comet.opik.domain.OptimizerBlueprint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizerBlueprintHistory(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) int page,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) int size,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) long total,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<OptimizerBlueprint> content) {

    public static OptimizerBlueprintHistory empty(int page) {
        return new OptimizerBlueprintHistory(page, 0, 0, List.of());
    }
}
