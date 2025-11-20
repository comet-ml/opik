package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request to batch update multiple trace threads")
public record TraceThreadBatchUpdate(
        @NotNull @NotEmpty @Size(min = 1, max = 1000) @Schema(description = "List of thread model IDs to update (max 1000)") Set<UUID> ids,
        @NotNull @Valid @Schema(description = "Update to apply to all threads") TraceThreadUpdate update,
        @Schema(description = "If true, merge tags with existing tags instead of replacing them. Default: false") Boolean mergeTags) {
}
