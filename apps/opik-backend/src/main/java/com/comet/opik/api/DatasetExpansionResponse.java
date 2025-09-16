package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetExpansionResponse(
        @Schema(description = "List of generated synthetic dataset items") List<DatasetItem> generatedSamples,
        @Schema(description = "Model used for generation", example = "gpt-4") String model,
        @Schema(description = "Total number of samples generated", example = "10") int totalGenerated,
        @Schema(description = "Generation timestamp", accessMode = Schema.AccessMode.READ_ONLY) Instant generationTime) {
}
