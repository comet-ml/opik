package com.comet.opik.api;

import com.comet.opik.domain.TraceEnrichmentOptions;
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
public record CreateDatasetItemsFromTracesRequest(
        @NotEmpty(message = "trace_ids must not be empty") @Schema(description = "Set of trace IDs to add to the dataset", requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> traceIds,
        @NotNull(message = "enrichment_options must not be null") @Schema(description = "Options for enriching trace data", requiredMode = Schema.RequiredMode.REQUIRED) TraceEnrichmentOptions enrichmentOptions) {
}
