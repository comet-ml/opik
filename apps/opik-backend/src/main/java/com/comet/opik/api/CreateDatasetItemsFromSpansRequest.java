package com.comet.opik.api;

import com.comet.opik.domain.SpanEnrichmentOptions;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateDatasetItemsFromSpansRequest(
        @NotEmpty(message = "span_ids must not be empty") @Schema(description = "Set of span IDs to add to the dataset", requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> spanIds,
        @NotNull(message = "enrichment_options must not be null") @Schema(description = "Options for enriching span data", requiredMode = Schema.RequiredMode.REQUIRED) SpanEnrichmentOptions enrichmentOptions) {
}
