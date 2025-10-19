package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TraceThreadBatchUpdate(
        @Schema(
            description = "List of thread model IDs to update",
            requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "Thread model IDs are required")
        @Size(min = 1, max = 1000, message = "Thread model IDs must contain between 1 and 1000 items")
        List<@NotNull UUID> threadModelIds,

        @Schema(
            description = "Update to apply to all threads",
            requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "Update is required")
        @Valid
        TraceThreadUpdate update) {
}
