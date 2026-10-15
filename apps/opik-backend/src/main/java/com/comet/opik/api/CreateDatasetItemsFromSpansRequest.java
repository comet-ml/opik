package com.comet.opik.api;

import com.comet.opik.api.validation.SupportedFieldMappingPaths;
import com.comet.opik.domain.SpanEnrichmentOptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateDatasetItemsFromSpansRequest(
        @NotEmpty(message = "span_ids must not be empty") @Schema(description = "Set of span IDs to add to the dataset", requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> spanIds,
        @NotNull(message = "enrichment_options must not be null") @Schema(description = "Options for enriching span data", requiredMode = Schema.RequiredMode.REQUIRED) SpanEnrichmentOptions enrichmentOptions,
        @Schema(description = "Optional evaluators to apply to the created items") List<@Valid EvaluatorItem> evaluators,
        @Schema(description = "Optional execution policy for the created items") @Valid ExecutionPolicy executionPolicy,
        @Schema(description = "Optional mapping of dataset item field name to a path into the span, e.g. 'input.input_text'. Takes precedence over the fields produced by enrichment_options. Ignored for test suite datasets.") @SupportedFieldMappingPaths @Size(max = 100, message = "field_mappings cannot exceed 100 entries") Map<String, String> fieldMappings) {
}
