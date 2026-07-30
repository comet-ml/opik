package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BatchDeleteByProject(
        @NotNull @Size(min = 1, max = 1000) @Schema(description = "Ids of the traces to delete") Set<@NotNull UUID> ids,
        @Schema(description = "Optional. Scopes the deletion to this project. When omitted, each trace's owning project is resolved automatically and the trace is deleted under its full key, so a trace can be deleted without knowing its project.") UUID projectId) {
}
